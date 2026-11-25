package io.klob.wallpapers.live;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

/** Native controls using the klob standard design tokens. */
final class AppStyle {
    static final int GREEN = 0xff00ff00;
    static final int SYSTEM = 0, LIGHT = 1, DARK = 2, CHEVRON = 3, RAINBOW = 4;
    final int surface, panel, text, muted, line, hover, accent, soft, accentBorder;
    final Typeface regular, semibold;
    private final Context context;

    static boolean light(Context context, int selection) {
        if (selection == LIGHT) return true;
        return selection == SYSTEM && (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO;
    }

    AppStyle(Context context, boolean light) {
        this.context = context;
        surface = light ? 0xfffafaf8 : 0xff101010;
        panel = light ? 0xffffffff : 0xff171717;
        text = light ? 0xff202020 : 0xffeeeeea;
        muted = light ? 0xff686862 : 0xffa5a5a0;
        line = light ? 0xffdeded7 : 0xff363634;
        hover = light ? 0xfff0f0eb : 0xff222220;
        accent = light ? 0xff007500 : GREEN;
        soft = light ? 0xffecf8e8 : 0xff172b15;
        accentBorder = light ? 0xff8bbf82 : 0xff3b6434;
        regular = context.getResources().getFont(R.font.space_grotesk_regular);
        semibold = context.getResources().getFont(R.font.space_grotesk_semibold);
    }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    Drawable shape(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    void text(TextView view, int size, int color, boolean strong) {
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(strong ? semibold : regular);
        view.setIncludeFontPadding(false);
    }

    Button button(boolean primary) {
        Button button = new Button(context);
        text(button, 14, primary ? 0xff101010 : text, true);
        button.setAllCaps(primary);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setStateListAnimator(null);
        button.setBackground(control(primary, false));
        return button;
    }

    Button iconButton(int icon, String label) {
        Button button = button(false);
        button.setBackground(control(false, true));
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setGravity(Gravity.CENTER);
        button.setForeground(icon(icon, text));
        button.setForegroundGravity(Gravity.CENTER);
        return button;
    }

    Drawable icon(int variant, int color) {
        return new Icon(variant, color, dp(20));
    }

    private Drawable control(boolean primary, boolean selectable) {
        StateListDrawable states = new StateListDrawable();
        if (selectable) states.addState(new int[]{android.R.attr.state_selected, android.R.attr.state_focused},
                shape(soft, accent, 8));
        states.addState(new int[]{android.R.attr.state_focused},
                shape(primary ? 0xff58ff58 : hover, accent, 8));
        if (selectable) states.addState(new int[]{android.R.attr.state_selected}, shape(soft, accentBorder, 8));
        states.addState(new int[]{}, shape(primary ? GREEN : panel, primary ? 0 : line, 8));
        return new RippleDrawable(ColorStateList.valueOf(primary ? 0x28101010 : 0x16787878), states, null);
    }

    void setupSwitch(Switch toggle) {
        text(toggle, 15, text, false);
        toggle.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{muted, text}));
        toggle.setMinHeight(dp(48));
        toggle.setPadding(0, dp(7), 0, dp(7));
        toggle.setSwitchPadding(dp(16));
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                new int[]{line, accent, muted}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accentBorder, muted}));
    }

    private static final class Icon extends Drawable {
        private final int variant, size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path moon = new Path();

        Icon(int variant, int color, int size) {
            this.variant = variant;
            this.size = size;
            paint.setColor(color);
            paint.setStrokeWidth(1.7f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            moon.moveTo(15.9f, 2.5f);
            moon.cubicTo(11.0f, 10.8f, 15.1f, 16.8f, 22f, 16.6f);
            moon.cubicTo(17.6f, 24.5f, 6.6f, 24.1f, 3.4f, 16.0f);
            moon.cubicTo(.7f, 9.0f, 7.6f, .8f, 15.9f, 2.5f);
            moon.close();
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            if (variant == SYSTEM) {
                canvas.drawRoundRect(2, 4, 22, 17, 2, 2, paint);
                canvas.drawLine(12, 17, 12, 21, paint);
                canvas.drawLine(8, 21, 16, 21, paint);
            } else if (variant == LIGHT) {
                canvas.drawCircle(12, 12, 4.2f, paint);
                for (int i = 0; i < 8; ++i) {
                    canvas.drawLine(12, 2, 12, 4.8f, paint);
                    canvas.rotate(45, 12, 12);
                }
            } else if (variant == DARK) {
                canvas.drawPath(moon, paint);
            } else if (variant == CHEVRON) {
                canvas.drawLine(8, 6, 14, 12, paint);
                canvas.drawLine(14, 12, 8, 18, paint);
            } else {
                canvas.drawArc(2, 4, 22, 24, 180, 180, false, paint);
                canvas.drawArc(5, 7, 19, 21, 180, 180, false, paint);
                canvas.drawArc(8, 10, 16, 18, 180, 180, false, paint);
            }
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
