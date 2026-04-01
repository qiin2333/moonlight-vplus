package com.limelight

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 崩溃日志查看和导出界面
 */
class CrashLogActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val crashLogs = mutableListOf<File>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_log)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.crash_logs_title)
        
        recyclerView = findViewById(R.id.crash_log_recycler_view)
        emptyView = findViewById(R.id.empty_view)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadCrashLogs()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    private fun loadCrashLogs() {
        crashLogs.clear()
        crashLogs.addAll(CrashHandler.getCrashLogFiles(this))
        
        if (crashLogs.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            recyclerView.adapter = CrashLogAdapter(crashLogs) { file ->
                showCrashLogOptions(file)
            }
        }
    }
    
    private fun showCrashLogOptions(file: File) {
        val options = arrayOf(
            getString(R.string.crash_log_view),
            getString(R.string.crash_log_share),
            getString(R.string.crash_log_copy),
            getString(R.string.crash_log_delete)
        )
        
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewCrashLog(file)
                    1 -> shareCrashLog(file)
                    2 -> copyCrashLogToClipboard(file)
                    3 -> deleteCrashLog(file)
                }
            }
            .show()
    }
    
    private fun viewCrashLog(file: File) {
        try {
            val content = file.readText()
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.crash_log_share) { _, _ ->
                    shareCrashLog(file)
                }
                .show()
        } catch (e: Exception) {
            e.printStackTrace()
            CrashHandler.logException(this, e, "ViewLog")
            Toast.makeText(this, "Failed to read log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareCrashLog(file: File) {
        try {
            // 调试信息
            LimeLog.info("Attempting to share file: ${file.absolutePath}")
            LimeLog.info("File exists: ${file.exists()}")
            LimeLog.info("File can read: ${file.canRead()}")
            LimeLog.info("File size: ${file.length()} bytes")
            
            // 确保文件存在且可读
            if (!file.exists()) {
                Toast.makeText(this, "File does not exist: ${file.name}", Toast.LENGTH_LONG).show()
                return
            }
            
            if (!file.canRead()) {
                Toast.makeText(this, "Cannot read file: ${file.name}", Toast.LENGTH_LONG).show()
                return
            }
            
            val authority = "${applicationContext.packageName}.update_fileprovider"
            LimeLog.info("Using authority: $authority")
            
            val uri = FileProvider.getUriForFile(this, authority, file)
            LimeLog.info("Generated URI: $uri")
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Crash Log: ${file.name}")
                putExtra(Intent.EXTRA_TEXT, "Crash log from ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 添加临时读取权限
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            
            // 检查是否有应用可以处理这个Intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.crash_log_share)))
            } else {
                // 如果没有应用可以处理，尝试以文本形式分享
                shareAsText(file)
            }
        } catch (e: IllegalArgumentException) {
            // FileProvider路径配置问题
            e.printStackTrace()
            LimeLog.severe("FileProvider configuration error: ${e.message}")
            // 记录到崩溃日志
            CrashHandler.logException(this, e, "ShareFile")
            Toast.makeText(this, "FileProvider error. Trying alternative method...", Toast.LENGTH_SHORT).show()
            shareAsText(file)
        } catch (e: Exception) {
            e.printStackTrace()
            LimeLog.severe("Failed to share crash log: ${e.message}")
            // 记录到崩溃日志
            CrashHandler.logException(this, e, "ShareFile")
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun shareAsText(file: File) {
        try {
            val content = file.readText()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Crash Log: ${file.name}")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.crash_log_share)))
        } catch (e: Exception) {
            e.printStackTrace()
            // 记录到崩溃日志
            CrashHandler.logException(this, e, "ShareText")
            Toast.makeText(this, "Failed to share as text: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun copyCrashLogToClipboard(file: File) {
        try {
            val content = file.readText()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Crash Log", content)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // 记录到崩溃日志
            CrashHandler.logException(this, e, "CopyClipboard")
            Toast.makeText(this, "Failed to copy: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deleteCrashLog(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_delete_confirm_title)
            .setMessage(getString(R.string.crash_log_delete_confirm_message, file.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    if (file.delete()) {
                        Toast.makeText(this, R.string.crash_log_deleted, Toast.LENGTH_SHORT).show()
                        loadCrashLogs()
                    } else {
                        Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    CrashHandler.logException(this, e, "DeleteLog")
                    Toast.makeText(this, "Failed to delete: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private inner class CrashLogAdapter(
        private val logs: List<File>,
        private val onItemClick: (File) -> Unit
    ) : RecyclerView.Adapter<CrashLogAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileName: TextView = view.findViewById(R.id.file_name)
            val fileDate: TextView = view.findViewById(R.id.file_date)
            val fileSize: TextView = view.findViewById(R.id.file_size)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_crash_log, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = logs[position]
            holder.fileName.text = file.name
            holder.fileDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
            holder.fileSize.text = "${file.length() / 1024} KB"
            holder.itemView.setOnClickListener { onItemClick(file) }
        }
        
        override fun getItemCount() = logs.size
    }
}
