package com.ahfaz.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Lesson(val id: Long, val title: String, val body: String, val created: String)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
            }
        }
        setContent { AhfazApp(
            onSpeak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ahfaz") },
            onStopSpeak = { tts?.stop() }
        ) }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AhfazApp(onSpeak: (String) -> Unit, onStopSpeak: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ahfaz_lessons", 0) }
    var lessons by remember {
        mutableStateOf(
            prefs.getString("items", "")!!.split("\n---AHFAZ---\n")
                .filter { it.contains("\t") }
                .mapNotNull { row ->
                    val p = row.split("\t", limit = 4)
                    if (p.size == 4) Lesson(p[0].toLongOrNull() ?: System.currentTimeMillis(), p[1], p[2], p[3]) else null
                }
        )
    }
    var screen by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf<Lesson?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var spokenText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun persist(updated: List<Lesson>) {
        lessons = updated
        prefs.edit().putString("items", updated.joinToString("\n---AHFAZ---\n") {
            "${it.id}\t${it.title.replace("\t", " ")}\t${it.body.replace("\t", " ")}\t${it.created}"
        }).apply()
    }

    fun addLesson(title: String, body: String) {
        if (body.isBlank()) {
            Toast.makeText(context, "أضف نص الدرس أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        val name = title.trim().ifBlank { "درس جديد ${lessons.size + 1}" }
        val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
        persist(listOf(Lesson(System.currentTimeMillis(), name, body.trim(), date)) + lessons)
        draftTitle = ""
        draftBody = ""
        status = "تم حفظ الدرس بنجاح"
        screen = "home"
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            status = "جارٍ قراءة الصورة..."
            try {
                val image = InputImage.fromFilePath(context, uri)
                // Latin recognizer supports many common Latin-script notes; ML Kit's bundled default
                // recognizer does not provide Arabic script OCR. Keep manual correction available.
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        draftBody = result.text
                        if (result.text.isBlank()) {
                            status = "لم أستطع استخراج نص واضح. اكتب النص يدويًا أو جرّب صورة أوضح."
                        } else {
                            status = "تم استخراج النص. راجعه وصحّح الأخطاء قبل الحفظ."
                        }
                        busy = false
                        screen = "add"
                    }
                    .addOnFailureListener {
                        busy = false
                        status = "تعذّر قراءة الصورة. يمكنك كتابة النص يدويًا."
                        screen = "add"
                    }
            } catch (_: Exception) {
                busy = false
                status = "تعذّر فتح الصورة. جرّب صورة أخرى."
                screen = "add"
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        spokenText = transcript
        val target = selected?.body.orEmpty()
        if (transcript.isBlank()) {
            status = "لم أتمكن من سماع كلام واضح. جرّب مرة أخرى."
        } else {
            val normalize: (String) -> String = { s ->
                s.lowercase(Locale.ROOT)
                    .replace(Regex("[ًٌٍَُِّْـ]"), "")
                    .replace(Regex("[أإآ]"), "ا")
                    .replace("ى", "ي")
                    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            val expectedWords = normalize(target).split(" ").filter { it.isNotBlank() }
            val saidWords = normalize(transcript).split(" ").filter { it.isNotBlank() }
            val matching = expectedWords.count { it in saidWords }
            val percent = if (expectedWords.isEmpty()) 0 else (matching * 100 / expectedWords.size)
            status = when {
                percent >= 85 -> "أحسنت! يبدو أن قراءتك قريبة من النص ($percent%). راجع الكلمات التي قد تكون فاتتك."
                percent >= 55 -> "هناك بعض الاختلافات ($percent%). راجع النص وحاول مرة أخرى."
                else -> "يبدو أنك أخطأت أو أن الصوت لم يُفهم جيدًا ($percent%). حاول مرة أخرى."
            }
            if (percent >= 85) onSpeak("أحسنت")
            else onSpeak("لقد أخطأت، حاول مرة أخرى")
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF66D9B3),
        secondary = androidx.compose.ui.graphics.Color(0xFF80CBC4),
        background = androidx.compose.ui.graphics.Color(0xFF101820),
        surface = androidx.compose.ui.graphics.Color(0xFF17232D)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("أحفظ", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (screen != "home") {
                            IconButton(onClick = { screen = "home"; status = "" }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                    },
                    actions = {
                        Text("رفيق المذاكرة  ", style = MaterialTheme.typography.labelMedium)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (status.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                when (screen) {
                    "home" -> {
                        Text("أهلًا بك 👋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("حوّل دروسك إلى نصوص محفوظة، ثم راجعها وسمّعها بصوتك.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { screen = "add"; draftTitle = ""; draftBody = ""; status = "" }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة درس")
                            }
                            OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("من صورة")
                            }
                        }
                        Text("دروسي (${lessons.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (lessons.isEmpty()) {
                            Card {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(42.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("لا توجد دروس بعد", fontWeight = FontWeight.Bold)
                                    Text("أضف نصًا أو اختر صورة للبدء.", textAlign = TextAlign.Center)
                                }
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(lessons, key = { it.id }) { lesson ->
                                    Card(onClick = { selected = lesson; screen = "study"; spokenText = ""; status = "" }) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(lesson.title, fontWeight = FontWeight.Bold)
                                                Text("${lesson.created} • ${lesson.body.take(72)}${if (lesson.body.length > 72) "…" else ""}", style = MaterialTheme.typography.bodySmall)
                                            }
                                            IconButton(onClick = { persist(lessons.filterNot { it.id == lesson.id }) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "add" -> {
                        Text("إضافة درس", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = draftTitle, onValueChange = { draftTitle = it }, label = { Text("عنوان الدرس") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(
                            value = draftBody, onValueChange = { draftBody = it },
                            label = { Text("النص المستخرج أو اكتبه هنا") },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            minLines = 6
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f), enabled = !busy) { Text("اختيار صورة") }
                            Button(onClick = { addLesson(draftTitle, draftBody) }, modifier = Modifier.weight(1f), enabled = !busy) { Text("حفظ الدرس") }
                        }
                    }
                    "study" -> {
                        val lesson = selected
                        if (lesson != null) {
                            Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("اقرأ النص بصوتك، ثم اضغط زر التسميع للتحقق التقريبي.")
                            Card(modifier = Modifier.weight(1f)) {
                                Text(lesson.body, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { onSpeak(lesson.body) }, modifier = Modifier.weight(1f)) { Text("استمع للنص") }
                                OutlinedButton(onClick = { onStopSpeak() }, modifier = Modifier.weight(1f)) { Text("إيقاف الصوت") }
                            }
                            Button(
                                onClick = {
                                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                                        status = "التعرّف الصوتي غير متاح على هذا الجهاز."
                                    } else {
                                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-TN")
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "اقرأ النص بصوت واضح")
                                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                        }
                                        try { speechLauncher.launch(intent) }
                                        catch (_: Exception) {
                                            status = "تعذّر تشغيل الميكروفون. تأكد من وجود تطبيق للتعرّف الصوتي."
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("بدأت التسميع — اضغط للتحدث")
                            }
                            if (spokenText.isNotBlank()) {
                                Text("ما فهمه الهاتف:", fontWeight = FontWeight.Bold)
                                Text(spokenText)
                            }
                        }
                    }
                }
            }
        }
    }
}
