package com.feurstagram.extension;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;

/** Resource-free iOS-style (UISwitch) toggle. */
final class ExpressiveSwitch extends CompoundButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private float position;
    private float press;
    private ValueAnimator movement;
    private ValueAnimator pressure;

    ExpressiveSwitch(Context context) {
        super(context);
        setButtonDrawable(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(59), widthSpec), resolveSize(dp(44), heightSpec));
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        float target = isChecked() ? 1f : 0f;
        if (movement != null) movement.cancel();
        if (!isLaidOut() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            position = target;
            invalidate();
            return;
        }
        movement = animateValue(position, target, value -> position = value);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (pressure != null) pressure.cancel();
        float target = pressed && isEnabled() ? 1f : 0f;
        if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            press = target;
            invalidate();
        } else {
            pressure = animateValue(press, target, value -> press = value);
        }
    }

    private interface Frame { void apply(float value); }

    private ValueAnimator animateValue(float from, float to, Frame frame) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(250L);
        animator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
        animator.addUpdateListener(value -> {
            frame.apply((float) value.getAnimatedValue());
            invalidate();
        });
        animator.start();
        return animator;
    }

    /** iOS switch colours: system green when on, dark fill when off, white knob. */
    private static final int IOS_ON = 0xFF30D158;
    private static final int IOS_OFF = 0xFF39393D;

    @Override
    protected void onDraw(Canvas canvas) {
        // iOS UISwitch metrics: 51 x 31 track, 27 knob, 2 inset; the knob
        // stretches sideways while pressed.
        float scale = getResources().getDisplayMetrics().density;
        float w = 51f * scale, h = 31f * scale, r = h / 2f;
        float left = (getWidth() - w) / 2f;
        float centerY = getHeight() / 2f;
        track.set(left, centerY - r, left + w, centerY + r);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(IOS_OFF, IOS_ON, position));
        if (!isEnabled()) paint.setAlpha(110);
        canvas.drawRoundRect(track, r, r, paint);

        float knob = 27f * scale, inset = 2f * scale;
        float stretch = 7f * scale * press;
        float offset = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1f - position : position;
        float travel = w - 2 * inset - knob - stretch;
        float knobLeft = left + inset + travel * offset;
        RectF k = new RectF(knobLeft, centerY - knob / 2f, knobLeft + knob + stretch, centerY + knob / 2f);
        paint.setColor(Color.argb(60, 0, 0, 0));
        canvas.drawRoundRect(new RectF(k.left, k.top + 1.5f * scale, k.right, k.bottom + 1.5f * scale), knob / 2f, knob / 2f, paint);
        paint.setColor(Color.WHITE);
        if (!isEnabled()) paint.setAlpha(160);
        canvas.drawRoundRect(k, knob / 2f, knob / 2f, paint);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return "android.widget.Switch";
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(isChecked());
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (movement != null) movement.cancel();
        if (pressure != null) pressure.cancel();
        position = isChecked() ? 1f : 0f;
        press = isPressed() ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (movement != null) movement.cancel();
        if (pressure != null) pressure.cancel();
        super.onDetachedFromWindow();
    }

    private int dp(int value) { return Settings.dp(getContext(), value); }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
}
