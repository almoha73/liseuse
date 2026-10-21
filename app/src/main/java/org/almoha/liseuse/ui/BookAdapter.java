package org.almoha.liseuse.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.almoha.liseuse.R;
import org.almoha.liseuse.model.Book;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.Holder> {

    public interface Callback {
        void onOpen(Book book);

        void onLongPress(Book book);
    }

    private final List<Book> items = new ArrayList<>();
    private final Callback callback;

    public BookAdapter(Callback callback) {
        this.callback = callback;
    }

    public void setItems(List<Book> books) {
        items.clear();
        items.addAll(books);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Book b = items.get(position);
        h.title.setText(b.title);
        h.author.setText(b.author == null || b.author.isEmpty() ? "Auteur inconnu" : b.author);
        if (b.totalChars > 0 && b.progressChar > 0) {
            int pct = (int) Math.min(100, 100L * b.progressChar / b.totalChars);
            h.progress.setText(h.progress.getContext().getString(org.almoha.liseuse.R.string.percent_read, pct));
            h.progress.setVisibility(View.VISIBLE);
        } else {
            h.progress.setVisibility(View.GONE);
        }
        h.cover.setImageBitmap(null);
        if (b.coverPath != null) {
            Bitmap bm = decode(new File(b.coverPath), 400);
            if (bm != null) h.cover.setImageBitmap(bm);
        }
        h.itemView.setOnClickListener(v -> callback.onOpen(b));
        h.itemView.setOnLongClickListener(v -> {
            callback.onLongPress(b);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static Bitmap decode(File f, int target) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        int max = Math.max(o.outWidth, o.outHeight);
        while (max / sample > target * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView author;
        final TextView progress;

        Holder(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            author = v.findViewById(R.id.author);
            progress = v.findViewById(R.id.progress);
        }
    }
}
