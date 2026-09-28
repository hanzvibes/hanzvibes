package com.termux.app.nativeui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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

        holder.body.setText(item.body);
        holder.meta.setText(item.label);

        int surface = Color.TRANSPARENT;
        int stroke = Color.TRANSPARENT;
        int strokeWidth = 0;
        int body = Color.rgb(232, 234, 237);
        int meta = Color.rgb(129, 136, 147);
        int horizontalPadding = dp(holder, 8);
        int verticalPadding = dp(holder, 7);
        float bodySize = 15f;
        Typeface face = Typeface.DEFAULT;

        boolean showMeta = item.label != null && !item.label.trim().isEmpty();

        switch (item.type) {
            case USER:
                surface = Color.rgb(24, 29, 23);
                stroke = Color.rgb(44, 58, 38);
                strokeWidth = dp(holder, 1);
                body = Color.rgb(239, 242, 236);
                horizontalPadding = dp(holder, 13);
                verticalPadding = dp(holder, 10);
                bodySize = 14.5f;
                showMeta = false;
                break;

            case ASSISTANT:
                surface = Color.TRANSPARENT;
                stroke = Color.TRANSPARENT;
                strokeWidth = 0;
                body = Color.rgb(232, 234, 237);
                horizontalPadding = dp(holder, 6);
                verticalPadding = dp(holder, 8);
                bodySize = 15f;
                showMeta = false;
                break;

            case THINKING:
                surface = Color.TRANSPARENT;
                stroke = Color.TRANSPARENT;
                strokeWidth = 0;
                body = Color.rgb(129, 136, 146);
                horizontalPadding = dp(holder, 6);
                verticalPadding = dp(holder, 4);
                bodySize = 12.5f;
                face = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
                showMeta = false;
                break;

            case STATUS:
                surface = Color.TRANSPARENT;
                stroke = Color.TRANSPARENT;
                strokeWidth = 0;
                body = Color.rgb(111, 120, 131);
                horizontalPadding = dp(holder, 6);
                verticalPadding = dp(holder, 4);
                bodySize = 11.5f;
                showMeta = false;
                break;

            case TOOL:
                surface = Color.rgb(15, 18, 22);
                stroke = Color.rgb(40, 46, 55);
                strokeWidth = dp(holder, 1);
                body = Color.rgb(203, 208, 214);
                meta = Color.rgb(142, 151, 164);
                horizontalPadding = dp(holder, 11);
                verticalPadding = dp(holder, 9);
                bodySize = 12.5f;
                face = Typeface.MONOSPACE;
                showMeta = true;
                break;

            case ERROR:
                surface = Color.rgb(34, 18, 19);
                stroke = Color.rgb(86, 39, 42);
                strokeWidth = dp(holder, 1);
                body = Color.rgb(255, 205, 208);
                meta = Color.rgb(255, 125, 132);
                horizontalPadding = dp(holder, 11);
                verticalPadding = dp(holder, 9);
                bodySize = 13f;
                showMeta = true;
                break;

            case DIFF:
                surface = Color.rgb(12, 16, 17);
                stroke = Color.rgb(38, 49, 50);
                strokeWidth = dp(holder, 1);
                body = Color.rgb(194, 207, 209);
                meta = Color.rgb(112, 178, 175);
                horizontalPadding = dp(holder, 11);
                verticalPadding = dp(holder, 9);
                bodySize = 12.5f;
                face = Typeface.MONOSPACE;
                showMeta = true;
                break;

            case CONSOLE:
                surface = Color.rgb(10, 12, 15);
                stroke = Color.rgb(30, 34, 40);
                strokeWidth = dp(holder, 1);
                face = Typeface.MONOSPACE;
                bodySize = 12.5f;
                showMeta = true;
                break;
        }

        holder.card.setCardBackgroundColor(surface);
        holder.card.setStrokeColor(stroke);
        holder.card.setStrokeWidth(strokeWidth);
        holder.card.setRadius(dp(holder, item.type == NativeMessage.Type.USER ? 16 : 12));

        holder.meta.setVisibility(showMeta ? View.VISIBLE : View.GONE);
        holder.meta.setTextColor(meta);

        holder.body.setTextColor(body);
        holder.body.setTypeface(face);
        holder.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySize);

        holder.content.setPadding(
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        );

        String accessibilityLabel = showMeta
            ? item.label + ". " + item.body
            : item.body;
        holder.itemView.setContentDescription(accessibilityLabel);
    }

    private static int dp(Holder holder, int value) {
        return Math.round(
            value * holder.itemView.getResources().getDisplayMetrics().density
        );
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final LinearLayout content;
        final TextView meta;
        final TextView body;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.native_message_card);
            content = itemView.findViewById(R.id.native_message_content);
            meta = itemView.findViewById(R.id.native_message_meta);
            body = itemView.findViewById(R.id.native_message_body);
        }
    }
}
