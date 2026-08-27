package com.example.litreader.ui.detail

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.litreader.App
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

class ThreadDetailActivity : AppCompatActivity() {
    private lateinit var bind: ActivityDetailBinding
    private lateinit var repo: BookRepository
    private var onlyOp = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        bind = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(bind.root)
        repo = BookRepository((application as App).database)

        val tid = intent.getStringExtra("tid") ?: return finish()
        title = intent.getStringExtra("title")

        bind.btnOp.setOnClickListener {
            onlyOp = !onlyOp
            bind.btnOp.text = if (onlyOp) "看全部" else "只看楼主"
            load(tid)
        }
        load(tid)
    }

    private fun load(tid: String) {
        bind.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val posts = repo.getThread(tid, onlyOp)
            val html = "<html><head><meta charset='utf-8'>" +
                "<style>body{font-size:16px;line-height:1.7;padding:12px}img{max-width:100%}</style>" +
                "</head><body>" + posts.joinToString("<hr/>") { it.contentHtml } + "</body></html>"
            bind.web.loadDataWithBaseURL("https://www.t66y.com", html, "text/html", "utf-8", null)
            bind.progress.visibility = View.GONE
        }
    }
}
