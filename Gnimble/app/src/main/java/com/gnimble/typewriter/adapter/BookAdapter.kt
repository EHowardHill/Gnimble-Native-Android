// BookAdapter.kt
package com.gnimble.typewriter.adapter

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gnimble.typewriter.R
import com.gnimble.typewriter.ShareActivity
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.databinding.ItemBookBinding
import java.text.SimpleDateFormat
import java.util.Locale

class BookAdapter(
    private val onBookClick: (Book) -> Unit
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
            if (book.coverPath != null) {
                Glide.with(binding.bookImage.context)
                    .load(book.coverPath)
                    .placeholder(R.drawable.gallery_thumbnail_24px)
                    .error(R.drawable.gallery_thumbnail_24px)
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

            // Click listener for the entire card
            binding.root.setOnClickListener {
                onBookClick(book)
            }
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