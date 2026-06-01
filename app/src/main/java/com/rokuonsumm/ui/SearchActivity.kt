package com.rokuonsumm.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokuonsumm.databinding.ActivitySearchBinding
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: SearchAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SearchAdapter(::handleResultClick)
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.requestFocus()
        }

        binding.etSearch.doAfterTextChanged { txt ->
            val q = txt?.toString().orEmpty()
            binding.btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            viewModel.setQuery(q)
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.results.collect { rows ->
                    adapter.submitList(rows)
                    updateHint(rows.size)
                }
            }
        }

        // 起動時にキーボードを出す
        binding.etSearch.requestFocus()
        binding.etSearch.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateHint(count: Int) {
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        when {
            q.isEmpty() -> {
                binding.tvHint.text = "キーワードで過去の発言を横断検索\n例: 民泊、熱、あやか"
                binding.tvHint.visibility = View.VISIBLE
            }
            count == 0 -> {
                binding.tvHint.text = "「$q」に一致する発言はありません"
                binding.tvHint.visibility = View.VISIBLE
            }
            else -> binding.tvHint.visibility = View.GONE
        }
    }

    private fun handleResultClick(r: SearchResult) {
        if (r.isSummary) {
            startActivity(
                Intent(this, DayTimelineActivity::class.java)
                    .putExtra(DayTimelineActivity.EXTRA_DATE_KEY, r.dateKey)
            )
        } else {
            startActivity(
                Intent(this, UtteranceDetailActivity::class.java)
                    .putExtra(UtteranceDetailActivity.EXTRA_TRANSCRIPT_ID, r.transcriptId)
            )
        }
    }
}
