package app.uebungsplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/** Einfacher Dateibrowser innerhalb des per SAF freigegebenen Ordnerbaums. */
class FileBrowserActivity : AppCompatActivity() {

    private var tree: Uri? = null
    private val stack = ArrayList<Pair<String, String>>() // (docId, Anzeigename); [0] = Wurzel
    private var entries: List<Saf.Entry> = emptyList()

    private lateinit var listView: ListView
    private lateinit var pathView: TextView
    private lateinit var emptyView: TextView
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (tree == null) finish()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Berechtigung bleibt dann nur für diese Sitzung bestehen
        }
        tree = uri
        prefs.edit().putString("browser_tree", uri.toString()).apply()
        stack.clear()
        stack.add(Pair(DocumentsContract.getTreeDocumentId(uri), ""))
        load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        listView = findViewById(R.id.files)
        pathView = findViewById(R.id.path)
        emptyView = findViewById(R.id.empty)

        findViewById<View>(R.id.btnUp).setOnClickListener { goUp() }
        findViewById<View>(R.id.btnChangeRoot).setOnClickListener { pickTree.launch(null) }
        listView.setOnItemClickListener { _, _, i, _ ->
            val e = entries[i]
            if (e.isDir) {
                stack.add(Pair(e.docId, e.name))
                load()
            } else {
                choose(e)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) finish()
            }
        })

        val saved = prefs.getString("browser_tree", null)
        val savedUri = saved?.let { Uri.parse(it) }
        val stillGranted = savedUri != null && contentResolver.persistedUriPermissions.any {
            it.uri == savedUri && it.isReadPermission && it.isWritePermission
        }
        if (savedUri == null || !stillGranted) {
            pickTree.launch(null)
        } else {
            tree = savedUri
            restoreStack(savedUri)
            load()
        }
    }

    private fun restoreStack(t: Uri) {
        stack.clear()
        stack.add(Pair(DocumentsContract.getTreeDocumentId(t), ""))
        try {
            val a = JSONArray(prefs.getString("browser_stack", "[]"))
            for (i in 1 until a.length()) {
                val p = a.getJSONArray(i)
                stack.add(Pair(p.getString(0), p.getString(1)))
            }
        } catch (e: Exception) {
            // Wurzel genügt
        }
    }

    private fun goUp(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        load()
        return true
    }

    private fun load() {
        val t = tree ?: return
        val dirId = stack.last().first
        pathView.text = "/" + stack.drop(1).joinToString("/") { it.second }
        val a = JSONArray()
        stack.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        prefs.edit().putString("browser_stack", a.toString()).apply()

        Thread {
            val result = try {
                Saf.list(this, t, dirId)
                    .filter { it.isDir || Saf.isAudio(it) }
                    .sortedWith(compareBy<Saf.Entry>({ !it.isDir }, { it.name.lowercase() }))
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null) {
                    if (stack.size > 1) {
                        stack.subList(1, stack.size).clear()
                        load()
                    } else {
                        Toast.makeText(this, R.string.browser_no_access, Toast.LENGTH_LONG).show()
                    }
                } else {
                    entries = result
                    listView.adapter = ArrayAdapter(
                        this, R.layout.row_file,
                        result.map { (if (it.isDir) "📁  " else "🎵  ") + it.name }
                    )
                    emptyView.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }.start()
    }

    private fun choose(e: Saf.Entry) {
        val t = tree ?: return
        val track = Track(t.toString(), e.docId, stack.last().first, e.name)
        setResult(RESULT_OK, toIntent(track))
        finish()
    }

    companion object {
        fun toIntent(t: Track): Intent = Intent()
            .putExtra("tree", t.tree).putExtra("doc", t.docId)
            .putExtra("parent", t.parentId).putExtra("name", t.name)

        fun trackFromIntent(i: Intent): Track? {
            val tree = i.getStringExtra("tree") ?: return null
            val doc = i.getStringExtra("doc") ?: return null
            val parent = i.getStringExtra("parent") ?: return null
            val name = i.getStringExtra("name") ?: return null
            return Track(tree, doc, parent, name)
        }
    }
}
