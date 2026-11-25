package io.klob.wallpapers.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class ModelPicker {
    private ModelPicker() {}

    static void show(Activity activity, Catalog catalog, Catalog.Model current,
            Consumer<Catalog.Model> onPick) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout panel = new LinearLayout(builder.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        float density = activity.getResources().getDisplayMetrics().density;
        int padding = Math.round(16 * density);
        panel.setPadding(padding, 0, padding, 0);

        EditText search = new EditText(builder.getContext());
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("Search model, brand or size");
        panel.addView(search);

        List<Catalog.Model> matches = new ArrayList<>();
        ArrayAdapter<Catalog.Model> adapter = new ArrayAdapter<Catalog.Model>(builder.getContext(),
                android.R.layout.simple_list_item_single_choice, matches) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                Catalog.Model model = getItem(position);
                row.setSingleLine(false);
                row.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                row.setMinHeight(Math.round(56 * density));
                row.setText(model.name + "\n" + model.manufacturer + " // " + model.width + " × " + model.height);
                return row;
            }
        };
        ListView list = new ListView(builder.getContext());
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(380 * density), 1));
        TextView empty = new TextView(builder.getContext());
        empty.setText("No matching models");
        empty.setPadding(padding, padding, padding, padding);
        panel.addView(empty);
        list.setEmptyView(empty);

        AlertDialog dialog = builder.setTitle("Device model").setView(panel)
                .setNegativeButton(android.R.string.cancel, null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            Catalog.Model picked = matches.get(position);
            dialog.dismiss();
            onPick.accept(picked);
        });

        Consumer<String> filter = query -> {
            String[] words = query.toLowerCase(Locale.ROOT).replace('×', 'x').trim().split("\\s+");
            matches.clear();
            for (Catalog.Model model : catalog.models) {
                String description = (model.name + " " + model.id + " " + model.manufacturer + " "
                        + model.family + " " + model.width + "x" + model.height).toLowerCase(Locale.ROOT);
                boolean include = true;
                for (String word : words) {
                    if (!description.contains(word)) {
                        include = false;
                        break;
                    }
                }
                if (include) matches.add(model);
            }
            list.clearChoices();
            adapter.notifyDataSetChanged();
            int selected = matches.indexOf(current);
            if (selected >= 0) list.setItemChecked(selected, true);
            list.setSelection(query.isEmpty() && selected >= 0 ? selected : 0);
        };
        filter.accept("");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter.accept(text.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }
}
