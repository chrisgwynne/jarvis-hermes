package com.jarvis.hermes

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.hermes.databinding.ActivityMemoesBinding

/**
 * Activity for managing voice memos.
 */
class MemoesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadMemos()
    }

    override fun onResume() {
        super.onResume()
        loadMemos()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.recyclerMemos.layoutManager = LinearLayoutManager(this)
    }

    private fun loadMemos() {
        val memos = VoiceMemoManager.getMemos(this)

        if (memos.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.recyclerMemos.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.recyclerMemos.visibility = View.VISIBLE

            binding.recyclerMemos.adapter = MemoAdapter(
                memos,
                onPlayClick = { memo -> playMemo(memo) },
                onDeleteClick = { memo -> deleteMemo(memo) }
            )
        }

        binding.textCount.text = "${memos.size} memo${if (memos.size != 1) "s" else ""}"
    }

    private fun playMemo(memo: VoiceMemoManager.Memo) {
        if (VoiceMemoManager.playMemo(this, memo.id)) {
            Toast.makeText(this, "Playing: ${memo.timestamp}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Could not play memo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteMemo(memo: VoiceMemoManager.Memo) {
        if (VoiceMemoManager.deleteMemo(this, memo.id)) {
            Toast.makeText(this, "Memo deleted", Toast.LENGTH_SHORT).show()
            loadMemos()
        } else {
            Toast.makeText(this, "Could not delete memo", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Simple adapter for memos list.
 */
class MemoAdapter(
    private val memos: List<VoiceMemoManager.Memo>,
    private val onPlayClick: (VoiceMemoManager.Memo) -> Unit,
    private val onDeleteClick: (VoiceMemoManager.Memo) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<MemoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val textTimestamp: android.widget.TextView = view.findViewById(R.id.textTimestamp)
        val textDuration: android.widget.TextView = view.findViewById(R.id.textDuration)
        val btnPlay: android.widget.Button = view.findViewById(R.id.btnPlay)
        val btnDelete: android.widget.Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val memo = memos[position]

        holder.textTimestamp.text = VoiceMemoManager.formatTimestamp(memo.timestamp)
        holder.textDuration.text = VoiceMemoManager.formatDuration(memo.durationMs)

        holder.btnPlay.setOnClickListener { onPlayClick(memo) }
        holder.btnDelete.setOnClickListener { onDeleteClick(memo) }
    }

    override fun getItemCount(): Int = memos.size
}