package com.termux.view.textselection;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.WcWidth;
import com.example.gemini.R;
import com.termux.view.TerminalView;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextSelectionCursorController implements CursorController {

    private final TerminalView terminalView;
    private final TextSelectionHandleView mStartHandle, mEndHandle;
    private String mStoredSelectedText;
    private boolean mIsSelectingText = false;
    private long mShowStartTime = System.currentTimeMillis();

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;

    private ActionMode mActionMode;
    public final int ACTION_COPY = 1;
    public final int ACTION_PASTE = 2;
    public final int ACTION_SELECT_ALL = 3;
    public final int ACTION_SHARE = 4;
    public final int ACTION_MORE = 5;

    public TextSelectionCursorController(TerminalView terminalView) {
        this.terminalView = terminalView;
        mStartHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT);
        mEndHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT);

        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight());
    }

    @Override
    public void show(MotionEvent event) {
        setInitialTextSelectionPosition(event);
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true);

        setActionModeCallBacks();
        mShowStartTime = System.currentTimeMillis();
        mIsSelectingText = true;
    }

    @Override
    public boolean hide() {
        if (!isActive()) return false;

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }

        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsSelectingText = false;

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }

    public void setInitialTextSelectionPosition(MotionEvent event) {
        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        mSelX1 = mSelX2 = columnAndRow[0];
        mSelY1 = mSelY2 = columnAndRow[1];

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        if (!" ".equals(screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1))) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && !"".equals(screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1 - 1, mSelY1))) {
                mSelX1--;
            }
            while (mSelX2 < terminalView.mEmulator.mColumns - 1 && !"".equals(screen.getSelectedText(mSelX2 + 1, mSelY1, mSelX2 + 1, mSelY1))) {
                mSelX2++;
            }
        }
    }

    public void selectAll() {
        if (terminalView.mEmulator == null) return;
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        mSelX1 = 0;
        mSelY1 = -scrollRows;
        mSelX2 = terminalView.mEmulator.mColumns - 1;
        mSelY2 = terminalView.mEmulator.mRows - 1;
        render();
        terminalView.invalidate();
    }

    public void shareSelectedText(String text) {
        if (TextUtils.isEmpty(text)) return;
        try {
            Context context = terminalView.getContext();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(intent, context.getString(R.string.share_text));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception ignored) {
        }
    }

    public void showMoreMenu() {
        Context context = terminalView.getContext();
        PopupMenu popup = new PopupMenu(context, terminalView, Gravity.CENTER);
        Menu menu = popup.getMenu();

        final int MENU_SELECT_URL = 101;
        final int MENU_SHARE_TRANSCRIPT = 102;
        final int MENU_RESET_TERMINAL = 103;

        menu.add(Menu.NONE, MENU_SELECT_URL, Menu.NONE, R.string.select_url);
        menu.add(Menu.NONE, MENU_SHARE_TRANSCRIPT, Menu.NONE, R.string.share_transcript);
        menu.add(Menu.NONE, MENU_RESET_TERMINAL, Menu.NONE, R.string.reset_terminal);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SELECT_URL:
                    extractAndShowUrls();
                    return true;
                case MENU_SHARE_TRANSCRIPT:
                    shareFullTranscript();
                    return true;
                case MENU_RESET_TERMINAL:
                    if (terminalView.mTermSession != null && terminalView.mTermSession.getEmulator() != null) {
                        terminalView.mTermSession.getEmulator().reset();
                        terminalView.invalidate();
                    }
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private void extractAndShowUrls() {
        if (terminalView.mEmulator == null) return;
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        String fullText = screen.getSelectedText(0, -scrollRows, terminalView.mEmulator.mColumns, terminalView.mEmulator.mRows);
        if (TextUtils.isEmpty(fullText)) {
            Toast.makeText(terminalView.getContext(), R.string.no_urls_found, Toast.LENGTH_SHORT).show();
            return;
        }

        Pattern pattern = Pattern.compile("https?://[a-zA-Z0-9.-]+(:[0-9]+)?(/[\\S]*)?");
        Matcher matcher = pattern.matcher(fullText);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        while (matcher.find()) {
            urls.add(matcher.group());
        }

        if (urls.isEmpty()) {
            Toast.makeText(terminalView.getContext(), R.string.no_urls_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] urlArray = urls.toArray(new String[0]);
        if (urlArray.length == 1) {
            openUrl(urlArray[0]);
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(terminalView.getContext());
            builder.setTitle(R.string.select_url);
            builder.setItems(urlArray, (dialog, which) -> openUrl(urlArray[which]));
            builder.setNegativeButton(android.R.string.cancel, null);
            builder.show();
        }
    }

    private void openUrl(String url) {
        try {
            Context context = terminalView.getContext();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(terminalView.getContext(), "Failed to open URL: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFullTranscript() {
        if (terminalView.mEmulator == null) return;
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        String fullText = screen.getSelectedText(0, -scrollRows, terminalView.mEmulator.mColumns, terminalView.mEmulator.mRows);
        shareSelectedText(fullText);
    }

    public void setActionModeCallBacks() {
        final ActionMode.Callback callback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                int show = MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT;

                ClipboardManager clipboard = (ClipboardManager) terminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text).setEnabled(clipboard != null && clipboard.hasPrimaryClip()).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_SELECT_ALL, Menu.NONE, R.string.select_all).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_SHARE, Menu.NONE, R.string.share_text).setShowAsAction(show);
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.text_selection_more);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!isActive()) {
                    return true;
                }

                switch (item.getItemId()) {
                    case ACTION_COPY:
                        String selectedText = getSelectedText();
                        terminalView.mTermSession.onCopyTextToClipboard(selectedText);
                        terminalView.stopTextSelectionMode();
                        break;
                    case ACTION_PASTE:
                        terminalView.stopTextSelectionMode();
                        terminalView.mTermSession.onPasteTextFromClipboard();
                        break;
                    case ACTION_SELECT_ALL:
                        selectAll();
                        break;
                    case ACTION_SHARE:
                        String textToShare = getSelectedText();
                        terminalView.stopTextSelectionMode();
                        shareSelectedText(textToShare);
                        break;
                    case ACTION_MORE:
                        mStoredSelectedText = getSelectedText();
                        terminalView.stopTextSelectionMode();
                        showMoreMenu();
                        break;
                }

                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }

        };

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mActionMode = terminalView.startActionMode(callback);
            return;
        }

        //noinspection NewApi
        mActionMode = terminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return callback.onCreateActionMode(mode, menu);
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // Ignore.
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                float fontWidth = (terminalView.mRenderer != null) ? terminalView.mRenderer.getFontWidth() : 20f;
                int lineSpacing = (terminalView.mRenderer != null && terminalView.mRenderer.getFontLineSpacing() > 0) ? terminalView.mRenderer.getFontLineSpacing() : 40;
                int topRow = terminalView.getTopRow();

                int x1 = Math.round(mSelX1 * fontWidth);
                int x2 = Math.round((mSelX2 + 1) * fontWidth);
                if (x1 > x2) {
                    int tmp = x1;
                    x1 = x2;
                    x2 = tmp;
                }

                int screenRow1 = mSelY1 - topRow;
                int screenRow2 = mSelY2 - topRow;

                int top = terminalView.mTopPadding + (screenRow1 * lineSpacing);
                int bottom = terminalView.mTopPadding + ((screenRow2 + 1) * lineSpacing) + mHandleHeight;

                int viewWidth = terminalView.getWidth();
                int viewHeight = terminalView.getHeight();

                top = Math.max(0, Math.min(top, viewHeight));
                bottom = Math.max(0, Math.min(bottom, viewHeight));
                x1 = Math.max(0, Math.min(x1, viewWidth));
                x2 = Math.max(0, Math.min(x2, viewWidth));

                outRect.set(x1, top, x2, bottom);
            }
        }, ActionMode.TYPE_FLOATING);
    }

    @Override
    public void updatePosition(TextSelectionHandleView handle, int x, int y) {
        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        if (handle == mStartHandle) {
            mSelX1 = terminalView.getCursorX(x);
            mSelY1 = terminalView.getCursorY(y);
            if (mSelX1 < 0) {
                mSelX1 = 0;
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows;

            } else if (mSelY1 > terminalView.mEmulator.mRows - 1) {
                mSelY1 = terminalView.mEmulator.mRows - 1;

            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY1 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY1 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX1 = getValidCurX(screen, mSelY1, mSelX1);

        } else {
            mSelX2 = terminalView.getCursorX(x);
            mSelY2 = terminalView.getCursorY(y);
            if (mSelX2 < 0) {
                mSelX2 = 0;
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows;
            } else if (mSelY2 > terminalView.mEmulator.mRows - 1) {
                mSelY2 = terminalView.mEmulator.mRows - 1;
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY2 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY2 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX2 = getValidCurX(screen, mSelY2, mSelX2);
        }

        terminalView.invalidate();
    }

    private int getValidCurX(TerminalBuffer screen, int cy, int cx) {
        String line = screen.getSelectedText(0, cy, cx, cy);
        if (!TextUtils.isEmpty(line)) {
            int col = 0;
            for (int i = 0, len = line.length(); i < len; i++) {
                char ch1 = line.charAt(i);
                if (ch1 == 0) {
                    break;
                }

                int wc;
                if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                    char ch2 = line.charAt(++i);
                    wc = WcWidth.width(Character.toCodePoint(ch1, ch2));
                } else {
                    wc = WcWidth.width(ch1);
                }

                final int cend = col + wc;
                if (cx > col && cx < cend) {
                    return cend;
                }
                if (cend == col) {
                    return col;
                }
                col = cend;
            }
        }
        return cx;
    }

    public void decrementYTextSelectionCursors(int decrement) {
        mSelY1 -= decrement;
        mSelY2 -= decrement;
    }

    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode();
        }
    }

    @Override
    public void onDetached() {
    }

    @Override
    public boolean isActive() {
        return mIsSelectingText;
    }

    public void getSelectors(int[] sel) {
        if (sel == null || sel.length != 4) {
            return;
        }

        sel[0] = mSelY1;
        sel[1] = mSelY2;
        sel[2] = mSelX1;
        sel[3] = mSelX2;
    }

    /** Get the currently selected text. */
    public String getSelectedText() {
        return terminalView.mEmulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mStoredSelectedText;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        mStoredSelectedText = null;
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /**
     * @return true if this controller is currently used to move the start selection.
     */
    public boolean isSelectionStartDragged() {
        return mStartHandle.isDragging();
    }

    /**
     * @return true if this controller is currently used to move the end selection.
     */
    public boolean isSelectionEndDragged() {
        return mEndHandle.isDragging();
    }

}
