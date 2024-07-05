package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.element_card.ButtonCard;
import com.limelight.binding.input.advance_setting.element_card.ElementCard;
import com.limelight.binding.input.advance_setting.element_card.GStickCard;
import com.limelight.binding.input.advance_setting.element_card.PadCard;
import com.limelight.binding.input.advance_setting.element_card.KStickCard;
import com.limelight.binding.input.advance_setting.element_card.SwitchCard;

public class EditController {
    private int editColor = 0xF0FF0000;
    private int normalColor = 0xF0888888;
    private int elementMiniSize = 50;

    private Context context;
    private ControllerManager controllerManager;
    private FrameLayout layerEdit;
    private Element editElement;
    private ElementCard elementCard;
    private EditController myself;




    //edit views
    private TextView elementWidthInput;
    private TextView elementHeightInput;
    private TextView elementXInput;
    private TextView elementYInput;
    private Button addButton;
    private Button deleteButton;
    private LinearLayout addElementView;
    private FrameLayout editUi;
    private View operationPanel;


    //add views
    private EditText elementName;
    private Spinner elementType;
    private FrameLayout elementCardSlot;






    public EditController( ControllerManager controllerManager, FrameLayout layout,Context context) {
        this.context = context;
        this.controllerManager = controllerManager;
        this.layerEdit = layout;
        this.myself = this;

        addElementView = layout.findViewById(R.id.add_element_view);
        deleteButton = layout.findViewById(R.id.delete_element_button);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (editElement == null){
                    return;
                }
                WindowsController.TextWindowListener deleteListener = new WindowsController.TextWindowListener() {
                    @Override
                    public boolean onConfirmCLick() {
                        controllerManager.getElementController().deleteElement(editElement);
                        editElement = null;
                        return true;
                    }

                    @Override
                    public boolean onCancelClick() {
                        return true;
                    }

                };

                controllerManager.getWindowsController().openTextWindow(deleteListener, "是否删除:" + editElement.getElementName());
            }
        });
        layout.findViewById(R.id.add_element_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (addElementView.getVisibility() == View.VISIBLE){
                    addElementView.setVisibility(View.GONE);
                    deleteButton.setVisibility(View.VISIBLE);
                    ((Button) v).setText("新增");
                } else {
                    addElementView.setVisibility(View.VISIBLE);
                    deleteButton.setVisibility(View.GONE);
                    elementWidthInput.setText("200");
                    elementHeightInput.setText("200");
                    elementXInput.setText("500");
                    elementYInput.setText("500");
                    ((Button) v).setText("退出");
                }
            }
        });

        //edit view
        editUi = layout.findViewById(R.id.layer_edit_ui);
        elementWidthInput = layout.findViewById(R.id.element_width);
        elementWidthInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpNumWindow((TextView) v,50,layout.getWidth());
            }
        });
        elementHeightInput = layout.findViewById(R.id.element_height);
        elementHeightInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpNumWindow((TextView) v,50,layout.getHeight());
            }
        });
        elementXInput = layout.findViewById(R.id.element_x);
        elementXInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpNumWindow((TextView) v,0,layout.getWidth());
            }
        });
        elementYInput = layout.findViewById(R.id.element_y);
        elementYInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpNumWindow((TextView) v,0,layout.getHeight());
            }
        });

        operationPanel = layout.findViewById(R.id.operation_panel);
        operationPanel.setOnTouchListener(new View.OnTouchListener() {
            int lastX;
            int lastY;
            int lastDistanceX;
            int lastDistanceY;
            //是否要选择element标志
            //单指点击动作记录标识
            boolean isSelect = false;
            //双指点击动作记录标识
            boolean isHideAction = false;
            //两根手指变一根手指的标志，不然抬起一根手指后，element的位置可能会跳变。
            boolean twoToOne = false;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                int pointerCount = event.getPointerCount();

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        // 单指按下时选中按钮
                        if (pointerCount == 1) {
                            lastX = (int) event.getX();
                            lastY = (int) event.getY();
                            isSelect = true;
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        isSelect = false;
                        if (pointerCount == 2){
                            lastDistanceX = (int) Math.abs(event.getX(1) - event.getX(0));
                            lastDistanceY = (int) Math.abs(event.getY(1) - event.getY(0));
                            isHideAction = true;
                        }
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float moveX = event.getX() - lastX;
                        float moveY = event.getY() - lastY;
                        // 计算移动距离,小距离不算做移动，跳过
                        float distance = (float) Math.sqrt(moveX * moveX + moveY * moveY);
                        if (distance < 2){
                            break;
                        }
                        if (twoToOne){
                            lastX = (int) event.getX();
                            lastY = (int) event.getY();
                            twoToOne = false;
                            break;
                        }
                        isSelect = false;
                        isHideAction = false;
                        // 单指移动时移动按钮
                        if (pointerCount == 1 && editElement != null) {
                            int x = (int) event.getX();
                            int y = (int) event.getY();
                            int dx = x - lastX;
                            int dy = y - lastY;
                            lastX = x;
                            lastY = y;
                            //坐标是中心点的坐标，不是右上角
                            int elementCentralX = editElement.getCentralX() + dx;
                            int elementCentralY = editElement.getCentralY() + dy;
                            elementCentralX = Math.min(elementCentralX,operationPanel.getWidth());
                            elementCentralX = Math.max(elementCentralX,0);
                            elementCentralY = Math.min(elementCentralY,operationPanel.getHeight());
                            elementCentralY = Math.max(elementCentralY,0);
                            elementXInput.setText(String.valueOf(elementCentralX));
                            elementYInput.setText(String.valueOf(elementCentralY));
                            editElement.setCentralX(elementCentralX);
                            editElement.setCentralY(elementCentralY);
                            return true;
                        } else if (pointerCount == 2 && editElement != null) {
                            int distanceX = (int) Math.abs(event.getX(1) - event.getX(0));
                            int distanceY = (int) Math.abs(event.getY(1) - event.getY(0));
                            int scalingX = distanceX - lastDistanceX;
                            int scalingY = distanceY - lastDistanceY;

                            int newWidth = editElement.getParamWidth()+ scalingX;
                            int newHeight = editElement.getParamHeight()+ scalingY;
                            newWidth = Math.min(newWidth, operationPanel.getWidth());
                            newWidth = Math.max(newWidth, elementMiniSize);
                            newHeight = Math.min(newHeight,operationPanel.getHeight());
                            newHeight = Math.max(newHeight,elementMiniSize);
                            lastDistanceX = distanceX;
                            lastDistanceY = distanceY;
                            elementWidthInput.setText(String.valueOf(newWidth));
                            elementHeightInput.setText(String.valueOf(newHeight));
                            editElement.setParamWidth(newWidth);
                            editElement.setParamHeight(newHeight);
                            return true;
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        twoToOne = true;
                        break;

                    case MotionEvent.ACTION_UP:
                        if (isSelect){
                            Element element = controllerManager.getElementController().selectElement(lastX,lastY);
                            if (element == null){
                                return true;
                            }
                            if (editElement != null){
                                editElement.setNormalColor(normalColor);
                            }
                            editElement = element;
                            editElement.setNormalColor(editColor);
                            elementXInput.setText(String.valueOf(editElement.getCentralX()));
                            elementYInput.setText(String.valueOf(editElement.getCentralY()));
                            elementWidthInput.setText(String.valueOf(editElement.getParamWidth()));
                            elementHeightInput.setText(String.valueOf(editElement.getParamHeight()));
                            return true;
                        } else if (isHideAction){
                            if (editUi.getVisibility() == View.GONE){
                                editUi.setVisibility(View.VISIBLE);
                                controllerManager.getMenuController().showMenu();
                            } else {
                                editUi.setVisibility(View.GONE);
                                controllerManager.getMenuController().hideMenu();
                            }
                        } else {
                            if (editElement != null){
                                controllerManager.getElementController().saveElement(editElement);
                            }
                        }

                        break;
                }
                return true;
            }
        });



        //add element
        //初始化elementCardSlot
        elementCardSlot = layout.findViewById(R.id.element_slot);
        elementCard = new ButtonCard(this,context);
        elementCardSlot.addView(elementCard.getView());
        elementType = layout.findViewById(R.id.element_type_spinner);
        elementName = layout.findViewById(R.id.element_name_edittext);
        elementType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (elementType.getSelectedItem().toString()){
                    case ElementBean.TYPE_BUTTON:
                        insertCard(new ButtonCard(myself,context));
                        break;
                    case ElementBean.TYPE_SWITCH:
                        insertCard(new SwitchCard(myself,context));
                        break;
                    case ElementBean.TYPE_K_PAD:
                    case ElementBean.TYPE_G_PAD:
                    case ElementBean.TYPE_PAD:
                        insertCard(new PadCard(myself,context));
                        break;
                    case ElementBean.TYPE_K_STICK:
                        insertCard(new KStickCard(myself,context));
                        break;
                    case ElementBean.TYPE_G_STICK:
                        insertCard(new GStickCard(myself,context));
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        //add button
        layout.findViewById(R.id.add_element_confirm_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = elementName.getText().toString();


                if (!name.matches("^.{1,10}$")){
                    Toast.makeText(context,"名称只能由1-10个数字、小写字母组成",Toast.LENGTH_SHORT).show();
                    return;
                }
                int elementCentralX = Integer.parseInt(elementXInput.getText().toString());
                int elementCentralY = Integer.parseInt(elementYInput.getText().toString());
                Long birthTime = System.currentTimeMillis();
                ElementBean elementBean = new ElementBean(
                        String.valueOf(birthTime),
                        name,
                        elementType.getSelectedItem().toString(),
                        elementCard.getTypeAttributes(),
                        elementCentralX,
                        elementCentralY,
                        Integer.parseInt(elementWidthInput.getText().toString()),
                        Integer.parseInt(elementHeightInput.getText().toString()),
                        100,
                        0xF0888888,
                        0xF00000FF,
                        0,
                        birthTime,
                        null
                );

                Element element = controllerManager.getElementController().addElement(elementBean);

                //选中刚刚添加的按钮
                if (element == null){
                    return;
                }
                if (editElement != null){
                    editElement.setNormalColor(normalColor);
                }
                editElement = element;
                editElement.setNormalColor(editColor);
                elementXInput.setText(String.valueOf(editElement.getCentralX()));
                elementYInput.setText(String.valueOf(editElement.getCentralY()));
                elementWidthInput.setText(String.valueOf(editElement.getParamWidth()));
                elementHeightInput.setText(String.valueOf(editElement.getParamHeight()));

            }
        });



    }

    private void jumpNumWindow(TextView inputNumView,int min ,int max){
        WindowsController.EditTextWindowListener inputNumListener = new WindowsController.EditTextWindowListener() {
            @Override
            public boolean onConfirmClick(String text) {
                if (text.equals("")){
                    Toast.makeText(context,"请输入" + min + "~" + max + "的数字",Toast.LENGTH_SHORT).show();
                    return false;
                }
                int value = Integer.parseInt(text);
                if (value > max || value < min){
                    Toast.makeText(context,"请输入" + min + "~" + max + "的数字",Toast.LENGTH_SHORT).show();
                    return false;
                }
                inputNumView.setText(String.valueOf(value));

                if (editElement == null){
                    return true;
                }
                if (inputNumView == elementWidthInput){
                    editElement.setParamWidth(value);
                } else if (inputNumView == elementHeightInput){
                    editElement.setParamHeight(value);
                } else if (inputNumView == elementXInput){
                    editElement.setCentralX(value);
                } else if (inputNumView == elementYInput){
                    editElement.setCentralY(value);
                }
                return true;
            }

            @Override
            public boolean onCancelClick(String text) {
                return true;
            }

        };

        controllerManager.getWindowsController().openEditTextWindow(inputNumListener,
                inputNumView.getText().toString(), "","", InputType.TYPE_CLASS_NUMBER);
    }



    private void insertCard(ElementCard elementCard){
        elementCardSlot.removeView(this.elementCard.getView());
        this.elementCard = elementCard;
        elementCardSlot.addView(this.elementCard.getView());
    }



    public void open(){
        layerEdit.setVisibility(View.VISIBLE);
        controllerManager.getSettingController().hideFloat();
    }

    public void close(){
        layerEdit.setVisibility(View.GONE);
        controllerManager.getSettingController().displayFloat();
        if (editElement != null){
            editElement.setNormalColor(normalColor);
        }
        editElement = null;
    }


    public void jumpDeviceLayout(TextView valueTextView){
        WindowsController.DeviceWindowListener keySelectListener = new WindowsController.DeviceWindowListener() {
            @Override
            public void onElementClick(String text, String tag) {
                elementName.setText(text);
                valueTextView.setTag(tag);
                valueTextView.setText(text);
            }
        };
        controllerManager.getWindowsController().openDeviceWindow(keySelectListener);


    }
}
