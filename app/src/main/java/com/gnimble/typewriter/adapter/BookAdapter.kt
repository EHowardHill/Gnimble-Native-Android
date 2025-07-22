// BookAdapter.kt
package com.gnimble.typewriter.adapter

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gnimble.typewriter.R
import com.gnimble.typewriter.ShareActivity
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.databinding.ItemBookBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.core.net.toUri

class BookAdapter(
    private val onBookClick: (Book) -> Unit,
    private val onRenameBook: (Book, String) -> Unit,
    private val onDeleteBook: (Book) -> Unit,
    private val onChangeCover: (Book) -> Unit
) : ListAdapter<Book, BookAdapter.BookViewHolder>(BookDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BookViewHolder(
        private val binding: ItemBookBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(book: Book) {
            binding.bookTitle.text = book.title
            binding.bookSubtitle.text = if (book.subtitle.isNotEmpty()) {
                book.subtitle
            } else {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                "Last edited: ${dateFormat.format(book.lastEdited)}"
            }

            // Load cover image if available, otherwise show default
            if (!book.coverPath.isNullOrEmpty()) {
                Glide.with(binding.bookImage.context)
                    .load(book.coverPath.toUri())
                    .placeholder(R.drawable.gallery_thumbnail_24px)
                    .error(R.drawable.gallery_thumbnail_24px)
                    .centerCrop()
                    .into(binding.bookImage)
            } else {
                binding.bookImage.setImageResource(R.drawable.gallery_thumbnail_24px)
            }

            binding.shareButton.setOnClickListener {
                val context = binding.root.context
                val intent = Intent(context, ShareActivity::class.java).apply {
                    putExtra("book_id", book.id)
                    putExtra("book_title", book.title)
                    putExtra("book_subtitle", book.subtitle)
                    putExtra("book_content", book.storyContent)
                    putExtra("book_cover_path", book.coverPath)
                }
                context.startActivity(intent)
            }

            binding.menuButton.setOnClickListener {
                showBottomMenu(book)
            }

            // Click listener for the entire card
            binding.root.setOnClickListener {
                onBookClick(book)
            }
        }

        private fun showBottomMenu(book: Book) {
            val context = binding.root.context
            val bottomSheetDialog = BottomSheetDialog(context)

            // Create the layout for the bottom sheet
            val bottomSheetView = LayoutInflater.from(context).inflate(
                R.layout.bottom_sheet_book_menu, null
            )

            bottomSheetDialog.setContentView(bottomSheetView)

            // Handle Change Cover click
            bottomSheetView.findViewById<ViewGroup>(R.id.menu_change_cover)?.setOnClickListener {
                bottomSheetDialog.dismiss()
                onChangeCover(book)
            }

            // Handle Rename click
            bottomSheetView.findViewById<ViewGroup>(R.id.menu_rename)?.setOnClickListener {
                bottomSheetDialog.dismiss()
                showRenameDialog(book)
            }

            // Handle Delete click
            bottomSheetView.findViewById<ViewGroup>(R.id.menu_delete)?.setOnClickListener {
                bottomSheetDialog.dismiss()
                showDeleteConfirmationDialog(book)
            }

            bottomSheetDialog.show()
        }

        private fun showRenameDialog(book: Book) {
            val context = binding.root.context
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_rename_book, null)
            val titleInput = dialogView.findViewById<TextInputEditText>(R.id.title_input)
            val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.title_layout)

            // Pre-fill with current title
            titleInput.setText(book.title)
            titleInput.setSelection(book.title.length)

            val dialog = AlertDialog.Builder(context)
                .setTitle("Rename Story")
                .setView(dialogView)
                .setPositiveButton("Rename") { dialog, _ ->
                    val newTitle = titleInput.text?.toString()?.trim()
                    if (!newTitle.isNullOrEmpty() && newTitle != book.title) {
                        onRenameBook(book, newTitle)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()

            dialog.show()

            // Validate input on the fly
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = true

            titleInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val text = s?.toString()?.trim()
                    val isValid = !text.isNullOrEmpty()
                    positiveButton.isEnabled = isValid
                    titleLayout.error = if (isValid) null else "Title is required"
                }
            })
        }

        private fun showDeleteConfirmationDialog(book: Book) {
            val context = binding.root.context
            AlertDialog.Builder(context)
                .setTitle("Delete Story")
                .setMessage("Are you sure you want to delete \"${book.title}\"? This action cannot be undone.")
                .setPositiveButton("Delete") { dialog, _ ->
                    onDeleteBook(book)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
            return oldItem == newItem
        }
    }
}