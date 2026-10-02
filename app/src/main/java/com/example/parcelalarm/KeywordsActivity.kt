package com.example.parcelalarm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.parcelalarm.databinding.ActivityKeywordsBinding

/**
 * 关键词管理页：添加 / 删除 / 清空用户自定义词组，保存到本地。
 */
class KeywordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeywordsBinding
    private val words = mutableListOf<String>()
    private lateinit var adapter: KeywordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeywordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        words.addAll(KeywordStore.load(this))
        adapter = KeywordAdapter(words) { position -> deleteWord(position) }
        binding.recyclerKeywords.layoutManager = LinearLayoutManager(this)
        binding.recyclerKeywords.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { addWord() }
        binding.btnClearAll.setOnClickListener {
            words.clear()
            persist()
        }
    }

    private fun addWord() {
        val text = binding.editKeyword.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            toast(getString(R.string.toast_empty))
            return
        }
        if (words.any { it == text }) {
            toast(getString(R.string.toast_exists))
            return
        }
        words.add(0, text)
        binding.editKeyword.setText("")
        persist()
    }

    private fun deleteWord(position: Int) {
        if (position in words.indices) {
            words.removeAt(position)
            persist()
        }
    }

    private fun persist() {
        KeywordStore.save(this, words)
        adapter.notifyDataSetChanged()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

/** 简单的关键词列表适配器。 */
class KeywordAdapter(
    private val items: List<String>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<KeywordAdapter.WordViewHolder>() {

    class WordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val word: TextView = view.findViewById(R.id.text_word)
        val delete: TextView = view.findViewById(R.id.btn_delete_word)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyword, parent, false)
        return WordViewHolder(view)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.word.text = items[position]
        holder.delete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size
}
