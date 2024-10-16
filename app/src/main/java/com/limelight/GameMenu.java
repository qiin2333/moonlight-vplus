package com.limelight;

import android.app.AlertDialog;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.input.KeyboardPacket;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provide options for ongoing Game Stream.
 * <p>
 * Shown on back action in game activity.
 */
public class GameMenu {

    private static final long TEST_GAME_FOCUS_DELAY = 10;
    private static final long KEY_UP_DELAY = 25;

    public static class MenuOption {
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
        }

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable);
        }
    }

    private final Game game;
    private final NvApp app;
    private final NvConnection conn;
    private final GameInputDevice device;

    public GameMenu(Game game, NvApp app, NvConnection conn, GameInputDevice device) {
        this.game = game;
        this.app = app;
        this.conn = conn;
        this.device = device;

        showMenu();
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }

    private static byte getModifier(short key) {
        switch (key) {
            case KeyboardTranslator.VK_LSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KeyboardTranslator.VK_LCONTROL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KeyboardTranslator.VK_LWIN:
                return KeyboardPacket.MODIFIER_META;
            case KeyboardTranslator.VK_MENU:
                return KeyboardPacket.MODIFIER_ALT;

            default:
                return 0;
        }
    }

    private void sendKeys(short[] keys) {
        final byte[] modifier = { (byte) 0 };

        for (short key : keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);

            // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event
            // (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] |= getModifier(key);
        }

        new Handler().postDelayed((() -> {

            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];

                // Remove the keys modifier before releasing the key
                modifier[0] &= ~getModifier(key);

                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }), KEY_UP_DELAY);
    }

    private void runWithGameFocus(Runnable runnable) {
        // Ensure that the Game activity is still active (not finished)
        if (game.isFinishing()) {
            return;
        }
        // Check if the game window has focus again, if not try again after delay
        if (!game.hasWindowFocus()) {
            new Handler().postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        // Game Activity has focus, run runnable
        runnable.run();
    }

    private void run(MenuOption option) {
        if (option.runnable == null) {
            return;
        }

        if (option.withGameFocus) {
            runWithGameFocus(option.runnable);
        } else {
            option.runnable.run();
        }
    }

    private void showMenuDialog(String title, MenuOption[] options) {
        AlertDialog.Builder builder = new AlertDialog.Builder(game);
        builder.setTitle(title);

        LayoutInflater inflater = game.getLayoutInflater();
        View customView = inflater.inflate(R.layout.custom_dialog, null);
        builder.setView(customView);
        AlertDialog dialog = builder.create();

        customView.findViewById(R.id.btnEsc).setOnClickListener((v) -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE}));
        customView.findViewById(R.id.btnWin).setOnClickListener((v) -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN}));
        customView.findViewById(R.id.btnHDR).setOnClickListener((v) -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_MENU, KeyboardTranslator.VK_B}));
        customView.findViewById(R.id.btnSleep).setOnClickListener((v) -> {
            sendKeys(new short[]{KeyboardTranslator.VK_LWIN, 88});
            new Handler().postDelayed((() -> {
                sendKeys(new short[]{85, 83});
            }), 200);
        });

        final ArrayAdapter<String> actions = new ArrayAdapter<>(game, android.R.layout.simple_list_item_1);

        for (MenuOption option : options) {
            actions.add(option.label);
        }

        ListView listView = customView.findViewById(R.id.gameMenuList);
        listView.setAdapter(actions);
        listView.setOnItemClickListener((AdapterView<?> parent, View view, int pos, long id) -> {
            String label = actions.getItem(pos);
            for (MenuOption option : options) {
                if (label != null && !label.equals(option.label)) {
                    continue;
                }

                run(option);
                break;
            }
            dialog.dismiss();
        });

        // 设置AlertDialog的背景透明度
        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams layoutParams = dialog.getWindow().getAttributes();
            layoutParams.alpha = 0.7f; // 设置透明度，0.0f - 1.0f，0表示完全透明，1表示不透明
            dialog.getWindow().setAttributes(layoutParams);
        }
        dialog.show();
    }

    private void showSpecialKeysMenu() {
        showMenuDialog(getString(R.string.game_menu_send_keys), new MenuOption[]{
//                new MenuOption(getString(R.string.game_menu_send_keys_esc),
//                        () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})),
                new MenuOption(getString(R.string.game_menu_send_keys_f11),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})),
                new MenuOption(getString(R.string.game_menu_send_keys_ctrl_v),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})),
//                new MenuOption(getString(R.string.game_menu_send_keys_win),
//                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})),
                new MenuOption(getString(R.string.game_menu_send_keys_win_d),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})),
                new MenuOption(getString(R.string.game_menu_send_keys_win_g),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})),
                new MenuOption(getString(R.string.game_menu_send_keys_alt_home),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_MENU, KeyboardTranslator.VK_HOME})),
                new MenuOption(getString(R.string.game_menu_send_keys_shift_tab),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})),
                new MenuOption(getString(R.string.game_menu_cancel), null),
        });
    }

    private void showMenu() {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard));
        options.add(new MenuOption(getString(R.string.game_menu_toggle_host_keyboard), true,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LCONTROL,
                        KeyboardTranslator.VK_O})));

        if (device != null) {
            options.addAll(device.getGameMenuOptions());
        }

        JsonArray cmdList = app.getCmdList();
        if (cmdList != null) {
            for (int i = 0; i < cmdList.size(); i++) {
                JsonObject cmd = cmdList.get(i).getAsJsonObject();
                options.add(new MenuOption(cmd.get("name").getAsString(), true, () -> {
                    try {
                        conn.sendSuperCmd(cmd.get("id").getAsString());
                    } catch (IOException | XmlPullParserException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
        }

        options.add(new MenuOption(getString(R.string.game_menu_toggle_performance_overlay),
                game::togglePerformanceOverlay));
        options.add(new MenuOption(getString(R.string.game_menu_send_keys), this::showSpecialKeysMenu));
        options.add(new MenuOption(getString(R.string.game_menu_disconnect), true, game::disconnect));
        options.add(new MenuOption("断开并退出串流", true, () -> {
            try {
                game.disconnect();
                conn.doStopAndQuit();
            } catch (IOException | XmlPullParserException e) {
                throw new RuntimeException(e);
            }
        }));
        options.add(new MenuOption(getString(R.string.game_menu_cancel), null));

        showMenuDialog("Game Menu", options.toArray(new MenuOption[0]));
    }
}
