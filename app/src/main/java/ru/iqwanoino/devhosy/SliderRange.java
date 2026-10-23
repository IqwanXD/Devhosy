package ru.iqwanoino.devhosy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

public class SliderRange extends SeekBar {
	
	/* ===== BASE SIZE (dp) ===== */
	private static final float TRACK_HEIGHT_DP = 14f;
	private static final float THUMB_HEIGHT_DP = 41f;
	private static final float THUMB_WIDTH_DP  = 3f;
	private static final float GAP_DP          = 5f;
	private static final float SIDE_OFFSET_DP  = 5f;
	private static final float DOT_RADIUS_DP   = 1.75f;
	private static final float DOT_GAP_DP      = 12f; // Jarak minimal antar titik dalam DP (Khas M3 agar tidak menumpuk)
	
	/* ===== COLORS ===== */
	private int trackActiveColor   = 0xFF0D47A1;
	private int trackInactiveColor = 0x4D0D47A1;
	private int thumbColor         = 0xFF0D47A1;
	private int dotActiveColor     = 0xFFFFFFFF;
	private int dotInactiveColor   = 0xFFBDBDBD;
	
	/* ===== DIMENSION ===== */
	private float density;
	private float trackHeight;
	private float thumbHeight;
	private float thumbWidth;
	private float thumbWidthAnim;
	private float gapX;
	private float sideOffset;
	private float dotRadius;
	private float dotGap; // Jarak dalam pixel nyata
	private float outerRadius;
	private float innerRadius;
	
	/* ===== PAINT ===== */
	private Paint trackPaint;
	private Paint thumbPaint;
	private Paint dotPaint;
	
	/* ===== ANIMATION ===== */
	private ValueAnimator widthAnimator;
	
	/* ===== CUSTOM LISTENER ===== */
	private SliderListener sliderListener;
	
	/* ===== CONSTRUCTOR ===== */
	public SliderRange(Context context) {
		super(context);
		init();
	}
	
	public SliderRange(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}
	
	/* ===== INIT ===== */
	private void init() {
		density = getResources().getDisplayMetrics().density;
		
		trackHeight = dp(TRACK_HEIGHT_DP);
		thumbHeight = dp(THUMB_HEIGHT_DP);
		thumbWidth  = dp(THUMB_WIDTH_DP);
		gapX        = dp(GAP_DP);
		sideOffset  = dp(SIDE_OFFSET_DP);
		dotRadius   = dp(DOT_RADIUS_DP);
		dotGap      = dp(DOT_GAP_DP);
		
		outerRadius = dp(7.5f);
		innerRadius = dp(2f);
		
		thumbWidthAnim = thumbWidth;
		
		trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		trackPaint.setStyle(Paint.Style.FILL);
		
		thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		thumbPaint.setStyle(Paint.Style.FILL);
		thumbPaint.setColor(thumbColor);
		
		dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		dotPaint.setStyle(Paint.Style.FILL);
		
		setBackground(null);
		setThumb(null);
		setSplitTrack(false);
		
		int px = (int) dp(8);
		int py = (int) dp(6);
		setPadding(px, py, px, py);
	}
	
	private float dp(float v) {
		return v * density;
	}
	
	/* ======================= */
	/* ===== LISTENER API ==== */
	/* ======================= */
	public interface SliderListener {
		void onValueChanged(SliderRange slider, int value, boolean fromUser);
		void onStartTracking(SliderRange slider);
		void onStopTracking(SliderRange slider);
	}
	
	public void setOnSliderChangeListener(SliderListener listener) {
		this.sliderListener = listener;
	}
	
	/* ======================= */
	/* ===== TOUCH SYSTEM ==== */
	/* ======================= */
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (!isEnabled()) return false;
		
		float x = event.getX();
		
		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
			getParent().requestDisallowInterceptTouchEvent(true);
			animateThumb(thumbWidthAnim, dp(2));
			if (sliderListener != null) sliderListener.onStartTracking(this);
			updateProgress(x, true);
			return true;
			
			case MotionEvent.ACTION_MOVE:
			updateProgress(x, true);
			return true;
			
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
			animateThumb(thumbWidthAnim, thumbWidth);
			if (sliderListener != null) sliderListener.onStopTracking(this);
			getParent().requestDisallowInterceptTouchEvent(false);
			return true;
		}
		return false;
	}
	
	private void updateProgress(float x, boolean fromUser) {
		float left  = getPaddingLeft();
		float right = getWidth() - getPaddingRight();
		
		float clamped = Math.max(left, Math.min(x, right));
		float percent = (clamped - left) / (right - left);
		
		int value = Math.round(percent * getMax());
		setProgress(value);
		
		if (sliderListener != null) {
			sliderListener.onValueChanged(this, value, fromUser);
		}
		invalidate();
	}
	
	/* ===== THUMB ANIMATION ===== */
	private void animateThumb(float from, float to) {
		if (widthAnimator != null) widthAnimator.cancel();
		
		widthAnimator = ValueAnimator.ofFloat(from, to);
		widthAnimator.setDuration(180);
		widthAnimator.setInterpolator(new DecelerateInterpolator());
		
		widthAnimator.addUpdateListener(a -> {
			thumbWidthAnim = (float) a.getAnimatedValue();
			invalidate();
		});
		
		widthAnimator.start();
	}
	
	/* ===== MEASURE ===== */
	@Override
	protected synchronized void onMeasure(int w, int h) {
		int mode = MeasureSpec.getMode(h);
		int size = MeasureSpec.getSize(h);
		
		int finalHeight;
		
		if (mode != MeasureSpec.EXACTLY) {
			thumbHeight = dp(41f);
			thumbWidth  = dp(3f);
			gapX        = dp(5f);
			sideOffset  = dp(5f);
			dotRadius   = dp(1.75f);
			trackHeight = dp(14f);
			outerRadius = dp(7.5f);
			innerRadius = dp(2f);
			
			finalHeight = (int) thumbHeight + getPaddingTop() + getPaddingBottom();
		} else {
			finalHeight = size;
			float usableHeight = finalHeight - getPaddingTop() - getPaddingBottom();
			
			thumbHeight = dp(41f);
			thumbWidth  = dp(3f);
			gapX        = dp(5f);
			sideOffset  = dp(5f);
			dotRadius   = dp(1.75f);
			
			trackHeight = usableHeight;
			float maxTrack = thumbHeight * 0.85f;
			if (trackHeight > maxTrack) {
				trackHeight = maxTrack;
			}
			
			outerRadius = trackHeight / 2f;
			innerRadius = thumbWidth / 2f;
		}
		
		setMeasuredDimension(MeasureSpec.getSize(w), finalHeight);
	}
	
	/* ===== DRAW ===== */
	@Override
	protected synchronized void onDraw(Canvas canvas) {
		int max = getMax();
		int value = getProgress();
		
		float centerY = getHeight() / 2f;
		float top     = centerY - trackHeight / 2f;
		float bottom  = centerY + trackHeight / 2f;
		
		float left  = getPaddingLeft();
		float right = getWidth() - getPaddingRight();
		
		float ratio = max == 0 ? 0 : (float) value / max;
		float thumbX = left + ratio * (right - left);
		
		/* ACTIVE TRACK */
		trackPaint.setColor(trackActiveColor);
		float activeEnd = thumbX - gapX;
		if (activeEnd > left) {
			drawTrack(canvas, left, top, activeEnd, bottom, outerRadius, innerRadius);
		}
		
		/* INACTIVE TRACK */
		trackPaint.setColor(trackInactiveColor);
		float inactiveStart = thumbX + gapX;
		if (inactiveStart < right) {
			drawTrack(canvas, inactiveStart, top, right, bottom, innerRadius, outerRadius);
		}
		
		/* ===== DOTS (HYBRID MATERIAL 3 LOGIC) ===== */
		if (max > 0) {
			float startX = left + sideOffset;
			float endX = right - sideOffset;
			float availableWidth = endX - startX;
			
			// Hitung kapasitas maksimum titik yang muat di layar dengan jarak aman (dotGap)
			int maxSafeDots = (int) (availableWidth / dotGap);
			
			int totalDots;
			float actualGap;
			boolean useProgressStep;
			
			// Jika nilai MAX lebih kecil dari kapasitas layar, ikuti angka MAX (Presisi)
			if (max <= maxSafeDots) {
				totalDots = max + 1;
				actualGap = availableWidth / max;
				useProgressStep = true;
			} else {
				// Jika nilai MAX kegedean, kunci jumlah titik berdasarkan ruang pixel (Safe Mode)
				totalDots = maxSafeDots;
				if (totalDots < 2) totalDots = 2;
				actualGap = availableWidth / (totalDots - 1);
				useProgressStep = false;
			}
			
			// Loop menggambar titik
			for (int i = 0; i < totalDots; i++) {
				float dotX = startX + (i * actualGap);
				
				// Sembunyikan titik jika bertubrukan langsung dengan ruang jeda (gap) Thumb
				if (dotX < thumbX - gapX || dotX > thumbX + gapX) {
					
					// Penentuan warna aktif/nonaktif yang akurat
					if (useProgressStep) {
						// Jika mode presisi, penentuan warna berdasarkan index langkah (i) jauh lebih akurat
						if (i <= value) {
							dotPaint.setColor(dotActiveColor);
						} else {
							dotPaint.setColor(dotInactiveColor);
						}
					} else {
						// Jika mode safe (skala besar), gunakan rasio posisi X di layar
						if (dotX <= thumbX) {
							dotPaint.setColor(dotActiveColor);
						} else {
							dotPaint.setColor(dotInactiveColor);
						}
					}
					
					canvas.drawCircle(dotX, centerY, dotRadius, dotPaint);
				}
			}
		}
		
		
		/* THUMB */
		canvas.drawRoundRect(
		new RectF(
		thumbX - thumbWidthAnim / 2f,
		centerY - thumbHeight / 2f,
		thumbX + thumbWidthAnim / 2f,
		centerY + thumbHeight / 2f
		),
		thumbWidthAnim / 2f,
		thumbWidthAnim / 2f,
		thumbPaint
		);
	}
	
	private void drawTrack(Canvas c, float l, float t, float r, float b, float rl, float rr) {
		Path path = new Path();
		path.addRoundRect(
		new RectF(l, t, r, b),
		new float[]{ rl, rl, rr, rr, rr, rr, rl, rl },
		Path.Direction.CW
		);
		c.drawPath(path, trackPaint);
	}
	
	@Override
	public synchronized void setProgress(int progress) {
		super.setProgress(progress);
		invalidate();
	}
	
	/* ===== COLOR SETTER ===== */
	public void setTrackActiveColor(int color) {
		trackActiveColor = color;
		invalidate();
	}
	
	public void setTrackInactiveColor(int color) {
		trackInactiveColor = color;
		invalidate();
	}
	
	public void setThumbColor(int color) {
		thumbColor = color;
		if (thumbPaint != null) thumbPaint.setColor(color);
		invalidate();
	}
	
	public void setDotActiveColor(int color) {
		dotActiveColor = color;
		invalidate();
	}
	
	public void setDotInactiveColor(int color) {
		dotInactiveColor = color;
		invalidate();
	}
}
