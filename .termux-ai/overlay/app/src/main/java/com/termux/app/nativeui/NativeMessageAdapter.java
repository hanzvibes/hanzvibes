package com.termux.app.nativeui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public final class NativeMessageAdapter extends RecyclerView.Adapter<NativeMessageAdapter.Holder> {

    private final List<NativeMessage> items = new ArrayList<>();

    public void submit(List<NativeMessage> messages) {
        items.clear();
        if (messages != null) items.addAll(messages);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_native_message, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        NativeMessage item = items.get(position);
        holder.meta.setText(item.label);
        holder.body.setText(item.body);

        int surface = Color.rgb(17, 20, 25);
        int stroke = Color.rgb(35, 40, 49);
        int body = Color.rgb(232, 234, 237);
        int meta = Color.rgb(129, 136, 147);
        Typeface face = Typeface.DEFAULT;

        switch (item.type) {
            case USER:
                surface = Color.rgb(29, 38, 23);
                stroke = Color.rgb(48, 70, 37);
                body = Color.rgb(238, 244, 234);
                meta = Color.rgb(154, 255, 69);
                break;
            case TOOL:
                surface = Color.rgb(20, 23, 29);
                stroke = Color.rgb(48, 55, 66);
                meta = Color.rgb(148, 157, 171);
                face = Typeface.MONOSPACE;
                break;
            case THINKING:
                surface = Color.TRANSPARENT;
                stroke = Color.TRANSPARENT;
                body = Color.rgb(132, 139, 149);
                meta = Color.rgb(103, 111, 121);
                face = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
                break;
            case STATUS:
                surface = Color.rgb(16, 21, 17);
                stroke = Color.rgb(37, 55, 40);
                body = Color.rgb(180, 191, 181);
                meta = Color.rgb(125, 211, 104);
                break;
            case ERROR:
                surface = Color.rgb(35, 19, 20);
                stroke = Color.rgb(91, 42, 45);
                body = Color.rgb(255, 204, 207);
                meta = Color.rgb(255, 122, 130);
                break;
            case DIFF:
                surface = Color.rgb(15, 19, 20);
                stroke = Color.rgb(43, 54, 55);
                body = Color.rgb(196, 207, 209);
                meta = Color.rgb(119, 180, 178);
                face = Typeface.MONOSPACE;
                break;
            case CONSOLE:
                surface = Color.rgb(10, 12, 15);
                stroke = Color.rgb(30, 34, 40);
                face = Typeface.MONOSPACE;
                break;
            case ASSISTANT:
            default:
                break;
        }

        holder.card.setCardBackgroundColor(surface);
        holder.card.setStrokeColor(stroke);
        holder.body.setTextColor(body);
        holder.meta.setTextColor(meta);
        holder.body.setTypeface(face);
        holder.itemView.setContentDescription(item.label + ". " + item.body);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView meta;
        final TextView body;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.native_message_card);
            meta = itemView.findViewById(R.id.native_message_meta);
            body = itemView.findViewById(R.id.native_message_body);
        }
    }
}
