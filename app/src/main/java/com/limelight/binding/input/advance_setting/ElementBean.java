package com.limelight.binding.input.advance_setting;

import java.util.Map;

public class ElementBean {
    public static final String TYPE_BUTTON = "BUTTON";
    public static final String TYPE_SWITCH = "SWITCH";
    public static final String TYPE_PAD = "PAD";
    public static final String TYPE_K_PAD = "KPAD";
    public static final String TYPE_G_PAD = "GPAD";
    public static final String TYPE_M_BUTTON = "MBUTTON";
    public static final String TYPE_K_STICK = "KSTICK";
    public static final String TYPE_K_ISTICK = "KISTICK";
    public static final String TYPE_G_STICK = "GSTICK";
    public static final String TYPE_G_ISTICK = "GISTICK";

    /**
     * id:element+timestamp
     */
    private String id;
    private String name;
    /*
    * type:
    * 0:BUTTON
    * 1:SWITCH
    * 2:PAD
    * 3:M-BUTTON
    * 4:K-STICK
    * 5:K-ISTICK
    * 6:G-STICK
    * 7:G-ISTICK
    * */
    private String type;
    private Map<String,String> typeAttributes;
    private int positionX;
    private int positionY;
    private int width;
    private int height;
    private int opacity;
    private int normalColor;
    private int pressedColor;
    private int layer;
    private long createTime;
    private Map<String,String> otherAttributes;

    public ElementBean(String id, String name, String type, Map<String, String> typeAttributes, int positionX, int positionY, int width, int height, int opacity, int normalColor, int pressedColor,int layer, long createTime, Map<String, String> otherAttributes) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.typeAttributes = typeAttributes;
        this.positionX = positionX;
        this.positionY = positionY;
        this.width = width;
        this.height = height;
        this.opacity = opacity;
        this.normalColor = normalColor;
        this.pressedColor = pressedColor;
        this.layer = layer;
        this.otherAttributes = otherAttributes;
        this.createTime = createTime;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String,String> getTypeAttributes() {
        return typeAttributes;
    }

    public void setTypeAttributes(Map<String,String> typeAttributes) {
        this.typeAttributes = typeAttributes;
    }

    public int getPositionX() {
        return positionX;
    }

    public void setPositionX(int positionX) {
        this.positionX = positionX;
    }

    public int getPositionY() {
        return positionY;
    }

    public void setPositionY(int positionY) {
        this.positionY = positionY;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getOpacity() {
        return opacity;
    }

    public void setOpacity(int opacity) {
        this.opacity = opacity;
    }

    public int getNormalColor() {
        return normalColor;
    }

    public void setNormalColor(int normalColor) {
        this.normalColor = normalColor;
    }

    public int getPressedColor() {
        return pressedColor;
    }

    public void setPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public Map<String,String> getOtherAttributes() {
        return otherAttributes;
    }

    public void setOtherAttributes(Map<String,String> otherAttributes) {
        this.otherAttributes = otherAttributes;
    }

    @Override
    public String toString() {
        return "ElementBean{" +
                "id='" + id + '\'' +
                ",name='" + name + '\'' +
                ", type=" + type +
                ", typeAttributes='" + typeAttributes + '\'' +
                ", positionX=" + positionX +
                ", positionY=" + positionY +
                ", width=" + width +
                ", height=" + height +
                ", opacity=" + opacity +
                ", normalColor=" + normalColor +
                ", pressedColor=" + pressedColor +
                ", layer=" + layer +
                ", otherAttributes='" + otherAttributes + '\'' +
                '}';
    }
}
