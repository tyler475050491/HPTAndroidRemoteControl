package com.hpt.adbtool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 投屏预览画布。
 *
 * 两种显示方式：
 *  1) 视频流（scrcpy + MediaCodec）——用内嵌的 TextureView 直接显示解码器输出，零拷贝、最流畅；
 *  2) 截图流（screencap 兜底）——在 onDraw 里画 Bitmap。
 *
 * 两种方式共用同一套「等比居中 + 旋转 + 手势坐标换算」逻辑。
 */
public class PreviewView extends FrameLayout {

    public interface OnTapListener {
        void onTap(float nx, float ny);
    }

    /** 全屏鼠控用手势回调（坐标已换算成画面内归一化坐标） */
    public interface OnGestureListener {
        void onDown(float nx, float ny);

        void onMove(float nx, float ny);

        void onUp(float nx, float ny);

        void onCancel();
    }

    private static final int MODE_EMPTY = 0;
    private static final int MODE_BITMAP = 1;
    private static final int MODE_VIDEO = 2;

    private Bitmap frame;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
            | Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private OnTapListener tapListener;
    private OnGestureListener gestureListener;
    private String emptyText = "";
    private int rotationDeg;
    private boolean touchDownInside;

    private TextureView texture;
    private int mode = MODE_EMPTY;
    private int videoW;
    private int videoH;

    public PreviewView(Context context) {
        this(context, null);
    }

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(Color.parseColor("#2E3A48"));
        textPaint.setColor(Color.parseColor("#7C8A99"));
    }

    public void setOnTapListener(OnTapListener l) {
        this.tapListener = l;
    }

    public void setOnGestureListener(OnGestureListener l) {
        this.gestureListener = l;
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        if (mode == MODE_EMPTY) {
            postInvalidate();
        }
    }

    // ---------------------------------------------------------------- 截图流

    public void setFrame(final Bitmap bmp) {
        setFrame(bmp, true);
    }

    /**
     * @param recycleOld 是否回收上一帧。视频流用的是循环复用的位图，不能回收（传 false）
     */
    public void setFrame(final Bitmap bmp, boolean recycleOld) {
        final Bitmap old = frame;
        frame = bmp;
        mode = bmp == null ? MODE_EMPTY : MODE_BITMAP;
        if (texture != null) {
            texture.setVisibility(View.GONE);
        }
        postInvalidate();
        if (recycleOld && old != null && old != bmp) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!old.isRecycled()) {
                        old.recycle();
                    }
                }
            }, 120);
        }
    }

    public Bitmap getFrame() {
        return frame;
    }

    // ---------------------------------------------------------------- 视频流

    /** 取（必要时创建）内嵌的 TextureView，供 MediaCodec 直接输出画面 */
    public TextureView getOrCreateTexture() {
        if (texture == null) {
            texture = new TextureView(getContext());
            texture.setOpaque(false);
            addView(texture, new LayoutParams(1, 1));
            texture.setVisibility(View.GONE);
        }
        return texture;
    }

    /** 进入 / 退出视频模式 */
    public void setVideoMode(boolean on, int width, int height) {
        videoW = width;
        videoH = height;
        mode = on ? MODE_VIDEO : MODE_EMPTY;
        if (texture != null) {
            texture.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (!on) {
            frame = null;
        }
        requestLayout();
        postInvalidate();
    }

    public boolean isVideoMode() {
        return mode == MODE_VIDEO;
    }

    /** 当前是否已有画面（视频模式或有位图帧），全屏按钮据此判断能不能全屏 */
    public boolean hasPicture() {
        if (mode == MODE_VIDEO) {
            computeDst();
            return !dst.isEmpty();
        }
        return mode == MODE_BITMAP && frame != null && !frame.isRecycled();
    }

    public SurfaceTexture getSurfaceTexture() {
        return texture == null ? null : texture.getSurfaceTexture();
    }

    /** 画面旋转角度（0/90/180/270），全屏时用于竖屏/横屏切换 */
    public void setRotationDegrees(int deg) {
        rotationDeg = ((deg % 360) + 360) % 360;
        if (texture != null) {
            texture.setRotation(rotationDeg);
        }
        requestLayout();
        postInvalidate();
    }

    public int getRotationDegrees() {
        return rotationDeg;
    }

    public RectF getFrameRect() {
        return dst;
    }

    /** 画面（内容）尺寸：视频模式取视频宽高，截图模式取位图宽高 */
    private int[] contentSize() {
        if (mode == MODE_VIDEO && videoW > 0 && videoH > 0) {
            return new int[]{videoW, videoH};
        }
        Bitmap bmp = frame;
        if (bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
            return new int[]{bmp.getWidth(), bmp.getHeight()};
        }
        return null;
    }

    private void computeDst() {
        int[] size = contentSize();
        if (size == null) {
            dst.setEmpty();
            return;
        }
        float vw = getWidth();
        float vh = getHeight();
        boolean swapped = rotationDeg % 180 != 0;
        float availW = swapped ? vh : vw;
        float availH = swapped ? vw : vh;
        float scale = Math.min(availW / size[0], availH / size[1]);
        float w = size[0] * scale;
        float h = size[1] * scale;
        dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        computeDst();
        if (texture != null) {
            int w = Math.round(dst.width());
            int h = Math.round(dst.height());
            if (w <= 0 || h <= 0) {
                w = 1;
                h = 1;
            }
            int x = Math.round(dst.left);
            int y = Math.round(dst.top);
            texture.layout(x, y, x + w, y + h);
            texture.setPivotX(w / 2f);
            texture.setPivotY(h / 2f);
            texture.setRotation(rotationDeg);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        textPaint.setTextSize(getResources().getDisplayMetrics().density * 12);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        computeDst();
        if (mode == MODE_VIDEO) {
            if (!dst.isEmpty()) {
                canvas.drawRect(dst, borderPaint);
            }
            return;
        }
        Bitmap bmp = frame;
        if (bmp == null || bmp.isRecycled() || dst.isEmpty()) {
            if (!emptyText.isEmpty()) {
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(emptyText, getWidth() / 2f, getHeight() / 2f, textPaint);
            }
            return;
        }
        float vw = getWidth();
        if (rotationDeg != 0) {
            canvas.save();
            canvas.rotate(rotationDeg, vw / 2f, getHeight() / 2f);
            canvas.drawBitmap(bmp, null, dst, paint);
            canvas.restore();
        } else {
            canvas.drawBitmap(bmp, null, dst, paint);
        }
        canvas.drawRect(dst, borderPaint);
    }

    /** 屏幕坐标 → 画面内归一化坐标（已反算旋转），不在画面矩形内返回 null */
    private float[] toNormalized(float px, float py) {
        computeDst();
        if (rotationDeg != 0) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            double rad = Math.toRadians(-rotationDeg);
            float dx = px - cx;
            float dy = py - cy;
            px = (float) (dx * Math.cos(rad) - dy * Math.sin(rad)) + cx;
            py = (float) (dx * Math.sin(rad) + dy * Math.cos(rad)) + cy;
        }
        if (dst.isEmpty() || !dst.contains(px, py)) {
            return null;
        }
        float nx = (px - dst.left) / dst.width();
        float ny = (py - dst.top) / dst.height();
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) {
            return null;
        }
        return new float[]{nx, ny};
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (contentSize() == null) {
            return super.onTouchEvent(event);
        }
        if (gestureListener != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float[] n = toNormalized(event.getX(), event.getY());
                    if (n == null) {
                        touchDownInside = false;
                        return true;
                    }
                    touchDownInside = true;
                    gestureListener.onDown(n[0], n[1]);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!touchDownInside) {
                        return true;
                    }
                    float[] n = toNormalized(event.getX(), event.getY());
                    if (n != null) {
                        gestureListener.onMove(n[0], n[1]);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float[] n = toNormalized(event.getX(), event.getY());
                    if (touchDownInside) {
                        if (n == null) {
                            n = new float[]{
                                    Math.max(0f, Math.min(1f, (event.getX() - dst.left) / dst.width())),
                                    Math.max(0f, Math.min(1f, (event.getY() - dst.top) / dst.height()))};
                        }
                        gestureListener.onUp(n[0], n[1]);
                    }
                    touchDownInside = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (touchDownInside) {
                        gestureListener.onCancel();
                    }
                    touchDownInside = false;
                    return true;
                }
                default:
                    return true;
            }
        }
        if (tapListener == null) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float[] n = toNormalized(event.getX(), event.getY());
            if (n == null) {
                return true;
            }
            tapListener.onTap(n[0], n[1]);
            return true;
        }
        return true;
    }
}
