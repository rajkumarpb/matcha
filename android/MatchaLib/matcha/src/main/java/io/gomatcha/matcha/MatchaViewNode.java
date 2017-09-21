package io.gomatcha.matcha;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.View;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import io.gomatcha.matcha.proto.paint.PbPaint;
import io.gomatcha.matcha.proto.touch.PbTouch;
import io.gomatcha.matcha.proto.view.PbView;

public class MatchaViewNode extends Object {
    MatchaViewNode parent;
    public MatchaView rootView;
    public long id;
    long buildId;
    long layoutId;
    long paintId;
    Map<Long, MatchaViewNode> children = new HashMap<Long, MatchaViewNode>();
    ArrayList<MatchaViewNode> childList = new ArrayList<MatchaViewNode>();
    MatchaChildView view;

    public MatchaViewNode(MatchaViewNode parent, MatchaView rootView, long id) {
        this.parent = parent;
        this.rootView = rootView;
        this.id = id;
    }

    public void setRoot(PbView.Root root) {
        PbView.LayoutPaintNode layoutPaintNode = root.getLayoutPaintNodesOrDefault(id, null);
        PbView.BuildNode buildNode = root.getBuildNodesOrDefault(id, null);

        // Create view
        if (this.view == null) {
            this.view = MatchaView.createView(buildNode.getBridgeName(), rootView.getContext(), this);
        }
        MatchaLayout layout = this.view.getLayout();

        // Build children
        Map<Long, MatchaViewNode> children = new HashMap<Long, MatchaViewNode>();
        ArrayList<MatchaViewNode> childList = new ArrayList<MatchaViewNode>();
        ArrayList<Long> removedKeys = new ArrayList<Long>();
        ArrayList<Long> addedKeys = new ArrayList<Long>();
        ArrayList<Long> unmodifiedKeys = new ArrayList<Long>();
        if (buildNode != null && this.buildId != buildNode.getBuildId()) {
            for (Long i : this.children.keySet()) {
                if (!root.containsBuildNodes(i)) {
                    removedKeys.add(i);
                }
            }
            for (Long i : buildNode.getChildrenList()) {
                MatchaViewNode prevChild = this.children.get(i);
                if (prevChild == null) {
                    addedKeys.add(i);

                    MatchaViewNode child = new MatchaViewNode(this, this.rootView, i);
                    childList.add(child);
                    children.put(i, child);
                } else {
                    unmodifiedKeys.add(i);
                    childList.add(prevChild);
                    children.put(i, prevChild);
                }
            }
        } else {
            children = this.children;
            childList = this.childList;
        }

        // Update children
        for (MatchaViewNode i : children.values()) {
            i.setRoot(root);
        }

        if (buildNode != null && this.buildId != buildNode.getBuildId()) {
            this.buildId = buildNode.getBuildId();

            // Update the views with native values
            this.view.setNode(buildNode);

            // Add/remove subviews
            if (this.view.isContainerView()) {
                ArrayList<View> childViews = new ArrayList<View>();
                for (MatchaViewNode i : childList) {
                    childViews.add(i.view);
                }
                this.view.setChildViews(childViews);
            } else {
                for (long i : addedKeys) {
                    MatchaViewNode childNode = children.get(i);
                    layout.addView(childNode.view);
                }
                for (long i : removedKeys) {
                    MatchaViewNode childNode = this.children.get(i);
                    layout.removeView(childNode.view);
                }
            }

            // Update gesture recognizers... TODO(KD):
            com.google.protobuf.Any gestures = buildNode.getValuesMap().get("gomatcha.io/matcha/touch");
            if (gestures != null) {
                try {
                    PbTouch.RecognizerList proto = gestures.unpack(PbTouch.RecognizerList.class);
                    for (PbTouch.Recognizer i : proto.getRecognizersList()) {
                        String type = i.getRecognizer().getTypeUrl();
                        if (type.equals("type.googleapis.com/matcha.touch.TapRecognizer")) {
                            this.view.matchaGestureRecognizer.tapGesture = i.getRecognizer();
                        } else if (type.equals("type.googleapis.com/matcha.touch.PressRecognizer")) {
                            this.view.matchaGestureRecognizer.pressGesture = i.getRecognizer();
                        } else if (type.equals("type.googleapis.com/matcha.touch.ButtonRecognizer")) {
                            this.view.matchaGestureRecognizer.buttonGesture = i.getRecognizer();
                        }
                    }
                    this.view.matchaGestureRecognizer.reload();
                    this.view.setClickable(proto.getRecognizersCount() > 0);
                } catch (InvalidProtocolBufferException e) {
                }
            }
        }

        // Layout subviews
        if (layoutPaintNode != null && this.layoutId != layoutPaintNode.getLayoutId()) {
            this.layoutId = layoutPaintNode.getLayoutId();

            for (int i = 0; i < layoutPaintNode.getChildOrderCount(); i++) {
                MatchaViewNode childNode = children.get(layoutPaintNode.getChildOrder(i));
                layout.bringChildToFront(childNode.view); // TODO(KD): Can be done more performantly.
            }

            double ratio = (float)this.view.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            double maxX = layoutPaintNode.getMaxx() * ratio;
            double maxY = layoutPaintNode.getMaxy() * ratio;
            double minX = layoutPaintNode.getMinx() * ratio;
            double minY = layoutPaintNode.getMiny() * ratio;

            if (this.parent == null) {
            } else if (this.parent.view.isContainerView()) {
            // } else if (this.parent.scrollView.getClass().isInstance(MatchaScrollView.class)) {
            } else {
                MatchaLayout.LayoutParams params = (MatchaLayout.LayoutParams)this.view.getLayoutParams();
                if (params == null) {
                    params = new MatchaLayout.LayoutParams();
                }
                params.left = minX;
                params.top = minY;
                params.right = maxX;
                params.bottom = maxY;
                this.view.setLayoutParams(params);
            }
        }

        // Paint scrollView
        if (layoutPaintNode != null & this.paintId != layoutPaintNode.getLayoutId()) {
            this.paintId = layoutPaintNode.getPaintId();

            PbPaint.Style paintStyle = layoutPaintNode.getPaintStyle();

            double ratio = (float)this.view.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT;
            GradientDrawable gd = new GradientDrawable();

            double cornerRadius = paintStyle.getCornerRadius();
            gd.setCornerRadius((float)(cornerRadius * ratio));

            if (paintStyle.hasBorderColor()) {
                gd.setStroke((int)(paintStyle.getBorderWidth() * ratio), Protobuf.newColor(paintStyle.getBorderColor()));
            } else {
                gd.setStroke(0, 0);
            }

            if (this.view instanceof MatchaImageView) {
                ((MatchaImageView)this.view).view.setCornerRadius((float)(cornerRadius*ratio));
                ((MatchaImageView)this.view).view.setBorderColor(Protobuf.newColor(paintStyle.getBorderColor()));
                ((MatchaImageView)this.view).view.setBorderWidth((float)(paintStyle.getBorderWidth()*ratio));
            }

            if (paintStyle.hasBackgroundColor()) {
                gd.setColor(Protobuf.newColor(paintStyle.getBackgroundColor()));
            } else {
                gd.setColor(Color.alpha(0));
            }
            this.view.setBackground(gd);

            this.view.setAlpha((float)(1.0 - paintStyle.getTransparency()));
        }

        this.children = children;
    }
}
