/*******************************************************************************
 * Copyright 2015 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.jogamp;

import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.OverlayLayout;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import TUIO.*;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Pool;

public class JoglAwtInput implements JoglInput, MouseMotionListener, MouseListener, MouseWheelListener, KeyListener, TuioListener {
	private final int maxPointers = 11;

	private final List<KeyEvent> keyEvents = new ArrayList<>();
	private final List<TouchEvent> touchEvents = new ArrayList<>();
	private final int[] touchX = new int[maxPointers];
	private final int[] touchY = new int[maxPointers];
	private final int[] deltaX = new int[maxPointers];
	private final int[] deltaY = new int[maxPointers];
	private int lastTouchX;
	private int lastTouchY;
	private int lastDeltaX;
	private int lastDeltaY;
	private boolean touchDown = false;
	private boolean justTouched = false;
	private final Set<Integer> keys = new HashSet<>();
	private final Set<Integer> pressedButtons = new HashSet<>();
	private InputProcessor processor;
	private Component component;
	private boolean catched = false;
	private Robot robot = null;
	private long currentEventTimeStamp;
	private JFrame frame;
	private final Dimension screenSize;
	private TuioClient tuioClient;
	private final Map<Long, Integer> tuioIDToPointer = new HashMap<>();
	private final Set<Integer> usedPointers = new HashSet<>();

	private final Pool<KeyEvent> usedKeyEvents = new Pool<KeyEvent>(16, 1000) {
		protected KeyEvent newObject () {
			return new KeyEvent();
		}
	};

	private final Pool<TouchEvent> usedTouchEvents = new Pool<TouchEvent>(16, 1000) {
		protected TouchEvent newObject () {
			return new TouchEvent();
		}
	};

	public JoglAwtInput (Component component) {
		setListeners(component);
		frame = findJFrame(component);
		screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		try {
			robot = new Robot(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
		} catch (HeadlessException | AWTException ignored) {
		}
	}

	public void startTuioClient() {
		tuioClient = new TuioClient();
		tuioClient.connect();
	}

	public void startTuioClient(int port) {
		tuioClient = new TuioClient(port);
		tuioClient.connect();
	}

	public void stopTuioClient() {
		if(tuioClient == null)
			return;

		tuioClient.disconnect();
		tuioClient = null;
	}

	private boolean tuioRunning() {
		return tuioClient != null && tuioClient.isConnected();
	}

	private int getFreePointer() {
		// pointer 0 is for mouse
		for(int i = 1; i < maxPointers; i++)
			if(!usedPointers.contains(i)) {
				usedPointers.add(i);
				return i;
			}
		return 0;
	}

	private void freePointer(int pointer) {
		usedPointers.remove(pointer);
	}

	public void setListeners (Component newComponent) {
		if (this.component != null) {
			component.removeMouseListener(this);
			component.removeMouseMotionListener(this);
			component.removeMouseWheelListener(this);
			component.removeKeyListener(this);
		}

		this.component = newComponent;
		component.addMouseListener(this);
		component.addMouseMotionListener(this);
		component.addMouseWheelListener(this);
		component.addKeyListener(this);
		component.setFocusTraversalKeysEnabled(false);
		frame = findJFrame(newComponent);
	}

	@Override
	public float getAccelerometerX () {
		return 0;
	}

	@Override
	public float getAccelerometerY () {
		return 0;
	}

	@Override
	public float getAccelerometerZ () {
		return 0;
	}

	@Override
	public void getTextInput (final TextInputListener listener, final String title, final String text, final String hint) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run () {
				String output = JOptionPane.showInputDialog(null, title, text);
				if (output != null)
					listener.input(output);
				else
					listener.canceled();

			}
		});
	}

	@Override
	public void getTextInput(TextInputListener textInputListener, String s, String s1, String s2, OnscreenKeyboardType onscreenKeyboardType) {

	}

	public void getPlaceholderTextInput (final TextInputListener listener, final String title, final String placeholder) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run () {
				JPanel panel = new JPanel(new FlowLayout());

				JPanel textPanel = new JPanel() {
					public boolean isOptimizedDrawingEnabled () {
						return false;
					}
				};

				textPanel.setLayout(new OverlayLayout(textPanel));
				panel.add(textPanel);

				final JTextField textField = new JTextField(20);
				textField.setAlignmentX(0.0f);
				textPanel.add(textField);

				final JLabel placeholderLabel = new JLabel(placeholder);
				placeholderLabel.setForeground(Color.GRAY);
				placeholderLabel.setAlignmentX(0.0f);
				textPanel.add(placeholderLabel, 0);

				textField.getDocument().addDocumentListener(new DocumentListener() {

					@Override
					public void removeUpdate (DocumentEvent arg0) {
						this.updated();
					}

					@Override
					public void insertUpdate (DocumentEvent arg0) {
						this.updated();
					}

					@Override
					public void changedUpdate (DocumentEvent arg0) {
						this.updated();
					}

					private void updated () {
						placeholderLabel.setVisible(textField.getText().length() == 0);
					}
				});

				JOptionPane pane = new JOptionPane(panel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null, null,
						null);

				pane.setInitialValue(null);
				pane.setComponentOrientation(JOptionPane.getRootFrame().getComponentOrientation());

				Border border = textField.getBorder();
				placeholderLabel.setBorder(new EmptyBorder(border.getBorderInsets(textField)));

				JDialog dialog = pane.createDialog(null, title);
				pane.selectInitialValue();

				dialog.addWindowFocusListener(new WindowFocusListener() {

					@Override
					public void windowLostFocus (WindowEvent arg0) {
					}

					@Override
					public void windowGainedFocus (WindowEvent arg0) {
						textField.requestFocusInWindow();
					}
				});

				dialog.setVisible(true);
				dialog.dispose();

				Object selectedValue = pane.getValue();

				if ((selectedValue instanceof Integer)
						&& (Integer) selectedValue == JOptionPane.OK_OPTION) {
					listener.input(textField.getText());
				} else {
					listener.canceled();
				}

			}
		});
	}

	@Override
	public int getX () {
		return lastTouchX;
	}

	@Override
	public int getX (int pointer) {
		if(pointer < maxPointers)
			return touchX[pointer];
		return 0;
	}

	@Override
	public int getY () {
		return lastTouchY;
	}

	@Override
	public int getY (int pointer) {
		if(pointer < maxPointers)
			return touchY[pointer];
		return 0;
	}

	@Override
	public boolean isKeyPressed (int key) {
		synchronized (this) {
			if (key == Input.Keys.ANY_KEY)
				return keys.size() > 0;
				else
					return keys.contains(key);
		}
	}

	@Override
	public boolean isTouched () {
		return touchDown;
	}

	@Override
	public boolean isTouched (int pointer) {
		if (pointer == 0)
			return touchDown;
		else
			return false;
	}

	@Override
	public float getPressure() {
		return getPressure(0);
	}

	@Override
	public float getPressure(int pointer) {
		return isTouched(pointer) ? 1 : 0;
	}

	@Override
	public void processEvents () {
		synchronized (this) {
			justTouched = false;

			if (processor != null) {
				InputProcessor processor = this.processor;

				int len = keyEvents.size();
				for (int i = 0; i < len; i++) {
					KeyEvent e = keyEvents.get(i);
					currentEventTimeStamp = e.timeStamp;
					switch (e.type) {
					case KeyEvent.KEY_DOWN:
						processor.keyDown(e.keyCode);
						break;
					case KeyEvent.KEY_UP:
						processor.keyUp(e.keyCode);
						break;
					case KeyEvent.KEY_TYPED:
						processor.keyTyped(e.keyChar);
					}
					usedKeyEvents.free(e);
				}

				len = touchEvents.size();
				for (int i = 0; i < len; i++) {
					TouchEvent e = touchEvents.get(i);
					currentEventTimeStamp = e.timeStamp;
					switch (e.type) {
					case TouchEvent.TOUCH_DOWN:
						processor.touchDown(e.x, e.y, e.pointer, e.button);
						justTouched = true;
						break;
					case TouchEvent.TOUCH_UP:
						processor.touchUp(e.x, e.y, e.pointer, e.button);
						break;
					case TouchEvent.TOUCH_DRAGGED:
						processor.touchDragged(e.x, e.y, e.pointer);
						break;
					case TouchEvent.TOUCH_MOVED:
						processor.mouseMoved(e.x, e.y);
						break;
					case TouchEvent.TOUCH_SCROLLED:
						processor.scrolled(e.scrollAmount, 0);
						break;
					}
					usedTouchEvents.free(e);
				}
			} else {
				int len = touchEvents.size();
				for (int i = 0; i < len; i++) {
					TouchEvent event = touchEvents.get(i);
					if (event.type == TouchEvent.TOUCH_DOWN) justTouched = true;
					usedTouchEvents.free(event);
				}

				len = keyEvents.size();
				for (int i = 0; i < len; i++) {
					usedKeyEvents.free(keyEvents.get(i));
				}
			}

			if (touchEvents.size() == 0) {
				for(int i = 0; i < maxPointers; i++) {
					deltaX[i] = 0;
					deltaY[i] = 0;
				}
			}

			keyEvents.clear();
			touchEvents.clear();
		}
	}

	@Override
	public void setCatchBackKey (boolean catchBack) {

	}

	@Override
	public void setOnscreenKeyboardVisible (boolean visible) {

	}

	@Override
	public void setOnscreenKeyboardVisible(boolean b, OnscreenKeyboardType onscreenKeyboardType) {

	}

	@Override
	public void mouseDragged (MouseEvent e) {
		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = 0;
			event.x = e.getX();
			event.y = e.getY();
			event.type = TouchEvent.TOUCH_DRAGGED;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[0] = event.x - touchX[0];
			deltaY[0] = event.y - touchY[0];
			touchX[0] = event.x;
			touchY[0] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			checkCatched(e);
			requestRendering();
		}
	}

	@Override
	public void mouseMoved (MouseEvent e) {
		if(tuioRunning())
			return;

		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = 0;
			event.x = e.getX();
			event.y = e.getY();
			event.type = TouchEvent.TOUCH_MOVED;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[0] = event.x - touchX[0];
			deltaY[0] = event.y - touchY[0];
			touchX[0] = event.x;
			touchY[0] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			checkCatched(e);
			requestRendering();
		}
	}

	@Override
	public void mouseClicked (MouseEvent arg0) {
	}

	@Override
	public void mouseEntered (MouseEvent e) {
		if(tuioRunning())
			return;

		touchX[0] = e.getX();
		touchY[0] = e.getY();
		checkCatched(e);
		requestRendering();
	}

	@Override
	public void mouseExited (MouseEvent e) {
		if(tuioRunning())
			return;

		checkCatched(e);
		requestRendering();
	}

	private void checkCatched (MouseEvent e) {
		if (catched && robot != null && component.isShowing()) {
			int x = Math.max(0, Math.min(e.getX(), component.getWidth()) - 1) + component.getLocationOnScreen().x;
			int y = Math.max(0, Math.min(e.getY(), component.getHeight()) - 1) + component.getLocationOnScreen().y;
			if (e.getX() < 0 || e.getX() >= component.getWidth() || e.getY() < 0 || e.getY() >= component.getHeight()) {
				robot.mouseMove(x, y);
			}
		}
	}

	private int toGdxButton (int swingButton) {
		if (swingButton == MouseEvent.BUTTON1) return Buttons.LEFT;
		if (swingButton == MouseEvent.BUTTON2) return Buttons.MIDDLE;
		if (swingButton == MouseEvent.BUTTON3) return Buttons.RIGHT;
		return Buttons.LEFT;
	}

	@Override
	public void mousePressed (MouseEvent e) {
		if(tuioRunning())
			return;

		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = 0;
			event.x = e.getX();
			event.y = e.getY();
			event.type = TouchEvent.TOUCH_DOWN;
			event.button = toGdxButton(e.getButton());
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[0] = event.x - touchX[0];
			deltaY[0] = event.y - touchY[0];
			touchX[0] = event.x;
			touchY[0] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			touchDown = true;
			pressedButtons.add(event.button);
			requestRendering();
		}
	}

	@Override
	public void mouseReleased (MouseEvent e) {
		if(tuioRunning())
			return;

		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = 0;
			event.x = e.getX();
			event.y = e.getY();
			event.button = toGdxButton(e.getButton());
			event.type = TouchEvent.TOUCH_UP;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[0] = event.x - touchX[0];
			deltaY[0] = event.y - touchY[0];
			touchX[0] = event.x;
			touchY[0] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			pressedButtons.remove(event.button);
			if (pressedButtons.size() == 0) touchDown = false;
			requestRendering();
		}
	}

	@Override
	public void mouseWheelMoved (MouseWheelEvent e) {
		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = 0;
			event.type = TouchEvent.TOUCH_SCROLLED;
			event.scrollAmount = e.getWheelRotation();
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);
			requestRendering();
		}
	}

	@Override
	public void keyPressed (java.awt.event.KeyEvent e) {
		synchronized (this) {
			KeyEvent event = usedKeyEvents.obtain();
			event.keyChar = 0;
			event.keyCode = translateKeyCode(e.getKeyCode());
			event.type = KeyEvent.KEY_DOWN;
			event.timeStamp = System.nanoTime();
			keyEvents.add(event);
			keys.add(event.keyCode);
			requestRendering();
		}
	}

	void requestRendering() {
		if(Gdx.graphics != null)
			Gdx.graphics.requestRendering();
	}

	@Override
	public void keyReleased (java.awt.event.KeyEvent e) {
		synchronized (this) {
			KeyEvent event = usedKeyEvents.obtain();
			event.keyChar = 0;
			event.keyCode = translateKeyCode(e.getKeyCode());
			event.type = KeyEvent.KEY_UP;
			event.timeStamp = System.nanoTime();
			keyEvents.add(event);
			keys.remove(event.keyCode);
			requestRendering();
		}
	}

	@Override
	public void keyTyped (java.awt.event.KeyEvent e) {
		synchronized (this) {
			KeyEvent event = usedKeyEvents.obtain();
			event.keyChar = e.getKeyChar();
			event.keyCode = 0;
			event.type = KeyEvent.KEY_TYPED;
			event.timeStamp = System.nanoTime();
			keyEvents.add(event);
			requestRendering();
		}
	}

	protected static int translateKeyCode (int keyCode) {
		if (keyCode == java.awt.event.KeyEvent.VK_ADD) return Input.Keys.PLUS;
		if (keyCode == java.awt.event.KeyEvent.VK_SUBTRACT) return Input.Keys.MINUS;
		if (keyCode == java.awt.event.KeyEvent.VK_0) return Input.Keys.NUM_0;
		if (keyCode == java.awt.event.KeyEvent.VK_1) return Input.Keys.NUM_1;
		if (keyCode == java.awt.event.KeyEvent.VK_2) return Input.Keys.NUM_2;
		if (keyCode == java.awt.event.KeyEvent.VK_3) return Input.Keys.NUM_3;
		if (keyCode == java.awt.event.KeyEvent.VK_4) return Input.Keys.NUM_4;
		if (keyCode == java.awt.event.KeyEvent.VK_5) return Input.Keys.NUM_5;
		if (keyCode == java.awt.event.KeyEvent.VK_6) return Input.Keys.NUM_6;
		if (keyCode == java.awt.event.KeyEvent.VK_7) return Input.Keys.NUM_7;
		if (keyCode == java.awt.event.KeyEvent.VK_8) return Input.Keys.NUM_8;
		if (keyCode == java.awt.event.KeyEvent.VK_9) return Input.Keys.NUM_9;
		if (keyCode == java.awt.event.KeyEvent.VK_A) return Input.Keys.A;
		if (keyCode == java.awt.event.KeyEvent.VK_B) return Input.Keys.B;
		if (keyCode == java.awt.event.KeyEvent.VK_C) return Input.Keys.C;
		if (keyCode == java.awt.event.KeyEvent.VK_D) return Input.Keys.D;
		if (keyCode == java.awt.event.KeyEvent.VK_E) return Input.Keys.E;
		if (keyCode == java.awt.event.KeyEvent.VK_F) return Input.Keys.F;
		if (keyCode == java.awt.event.KeyEvent.VK_G) return Input.Keys.G;
		if (keyCode == java.awt.event.KeyEvent.VK_H) return Input.Keys.H;
		if (keyCode == java.awt.event.KeyEvent.VK_I) return Input.Keys.I;
		if (keyCode == java.awt.event.KeyEvent.VK_J) return Input.Keys.J;
		if (keyCode == java.awt.event.KeyEvent.VK_K) return Input.Keys.K;
		if (keyCode == java.awt.event.KeyEvent.VK_L) return Input.Keys.L;
		if (keyCode == java.awt.event.KeyEvent.VK_M) return Input.Keys.M;
		if (keyCode == java.awt.event.KeyEvent.VK_N) return Input.Keys.N;
		if (keyCode == java.awt.event.KeyEvent.VK_O) return Input.Keys.O;
		if (keyCode == java.awt.event.KeyEvent.VK_P) return Input.Keys.P;
		if (keyCode == java.awt.event.KeyEvent.VK_Q) return Input.Keys.Q;
		if (keyCode == java.awt.event.KeyEvent.VK_R) return Input.Keys.R;
		if (keyCode == java.awt.event.KeyEvent.VK_S) return Input.Keys.S;
		if (keyCode == java.awt.event.KeyEvent.VK_T) return Input.Keys.T;
		if (keyCode == java.awt.event.KeyEvent.VK_U) return Input.Keys.U;
		if (keyCode == java.awt.event.KeyEvent.VK_V) return Input.Keys.V;
		if (keyCode == java.awt.event.KeyEvent.VK_W) return Input.Keys.W;
		if (keyCode == java.awt.event.KeyEvent.VK_X) return Input.Keys.X;
		if (keyCode == java.awt.event.KeyEvent.VK_Y) return Input.Keys.Y;
		if (keyCode == java.awt.event.KeyEvent.VK_Z) return Input.Keys.Z;
		if (keyCode == java.awt.event.KeyEvent.VK_ALT) return Input.Keys.ALT_LEFT;
		if (keyCode == java.awt.event.KeyEvent.VK_ALT_GRAPH) return Input.Keys.ALT_RIGHT;
		if (keyCode == java.awt.event.KeyEvent.VK_BACK_SLASH) return Input.Keys.BACKSLASH;
		if (keyCode == java.awt.event.KeyEvent.VK_COMMA) return Input.Keys.COMMA;
		if (keyCode == java.awt.event.KeyEvent.VK_DELETE) return Input.Keys.DEL;
		if (keyCode == java.awt.event.KeyEvent.VK_LEFT) return Input.Keys.DPAD_LEFT;
		if (keyCode == java.awt.event.KeyEvent.VK_RIGHT) return Input.Keys.DPAD_RIGHT;
		if (keyCode == java.awt.event.KeyEvent.VK_UP) return Input.Keys.DPAD_UP;
		if (keyCode == java.awt.event.KeyEvent.VK_DOWN) return Input.Keys.DPAD_DOWN;
		if (keyCode == java.awt.event.KeyEvent.VK_ENTER) return Input.Keys.ENTER;
		if (keyCode == java.awt.event.KeyEvent.VK_HOME) return Input.Keys.HOME;
		if (keyCode == java.awt.event.KeyEvent.VK_MINUS) return Input.Keys.MINUS;
		if (keyCode == java.awt.event.KeyEvent.VK_PERIOD) return Input.Keys.PERIOD;
		if (keyCode == java.awt.event.KeyEvent.VK_PLUS) return Input.Keys.PLUS;
		if (keyCode == java.awt.event.KeyEvent.VK_SEMICOLON) return Input.Keys.SEMICOLON;
		if (keyCode == java.awt.event.KeyEvent.VK_SHIFT) return Input.Keys.SHIFT_LEFT;
		if (keyCode == java.awt.event.KeyEvent.VK_SLASH) return Input.Keys.SLASH;
		if (keyCode == java.awt.event.KeyEvent.VK_SPACE) return Input.Keys.SPACE;
		if (keyCode == java.awt.event.KeyEvent.VK_TAB) return Input.Keys.TAB;
		if (keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE) return Input.Keys.DEL;
		if (keyCode == java.awt.event.KeyEvent.VK_CONTROL) return Input.Keys.CONTROL_LEFT;
		if (keyCode == java.awt.event.KeyEvent.VK_ESCAPE) return Input.Keys.ESCAPE;
		if (keyCode == java.awt.event.KeyEvent.VK_END) return Input.Keys.END;
		if (keyCode == java.awt.event.KeyEvent.VK_INSERT) return Input.Keys.INSERT;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD5) return Input.Keys.DPAD_CENTER;
		if (keyCode == java.awt.event.KeyEvent.VK_PAGE_UP) return Input.Keys.PAGE_UP;
		if (keyCode == java.awt.event.KeyEvent.VK_PAGE_DOWN) return Input.Keys.PAGE_DOWN;
		if (keyCode == java.awt.event.KeyEvent.VK_F1) return Input.Keys.F1;
		if (keyCode == java.awt.event.KeyEvent.VK_F2) return Input.Keys.F2;
		if (keyCode == java.awt.event.KeyEvent.VK_F3) return Input.Keys.F3;
		if (keyCode == java.awt.event.KeyEvent.VK_F4) return Input.Keys.F4;
		if (keyCode == java.awt.event.KeyEvent.VK_F5) return Input.Keys.F5;
		if (keyCode == java.awt.event.KeyEvent.VK_F6) return Input.Keys.F6;
		if (keyCode == java.awt.event.KeyEvent.VK_F7) return Input.Keys.F7;
		if (keyCode == java.awt.event.KeyEvent.VK_F8) return Input.Keys.F8;
		if (keyCode == java.awt.event.KeyEvent.VK_F9) return Input.Keys.F9;
		if (keyCode == java.awt.event.KeyEvent.VK_F10) return Input.Keys.F10;
		if (keyCode == java.awt.event.KeyEvent.VK_F11) return Input.Keys.F11;
		if (keyCode == java.awt.event.KeyEvent.VK_F12) return Input.Keys.F12;
		if (keyCode == java.awt.event.KeyEvent.VK_COLON) return Input.Keys.COLON;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD0) return Input.Keys.NUM_0;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD1) return Input.Keys.NUM_1;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD2) return Input.Keys.NUM_2;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD3) return Input.Keys.NUM_3;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD4) return Input.Keys.NUM_4;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD5) return Input.Keys.NUM_5;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD6) return Input.Keys.NUM_6;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD7) return Input.Keys.NUM_7;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD8) return Input.Keys.NUM_8;
		if (keyCode == java.awt.event.KeyEvent.VK_NUMPAD9) return Input.Keys.NUM_9;

		return Input.Keys.UNKNOWN;
	}

	@Override
	public void setInputProcessor (InputProcessor processor) {
		synchronized (this) {
			this.processor = processor;
		}
	}

	@Override
	public InputProcessor getInputProcessor () {
		return this.processor;
	}

	@Override
	public void vibrate (int milliseconds) {
	}

	@Override
	public boolean justTouched () {
		return justTouched;
	}

	@Override
	public boolean isButtonPressed (int button) {
		return pressedButtons.contains(button);
	}

	@Override
	public boolean isButtonJustPressed(int button) {
		return false;
	}

	@Override
	public void vibrate (long[] pattern, int repeat) {
	}

	@Override
	public void cancelVibrate () {
	}

	@Override
	public float getAzimuth () {
		return 0;
	}

	@Override
	public float getPitch () {
		return 0;
	}

	@Override
	public float getRoll () {
		return 0;
	}

	@Override
	public boolean isPeripheralAvailable (Peripheral peripheral) {
		return peripheral == Peripheral.HardwareKeyboard;
	}

	@Override
	public int getRotation () {
		return 0;
	}

	@Override
	public Orientation getNativeOrientation () {
		return Orientation.Landscape;
	}

	@Override
	public void setCursorCatched (boolean catched) {
		this.catched = catched;
		showCursor(!catched);
	}

	private void showCursor (boolean visible) {
		if (!visible) {
			Toolkit t = Toolkit.getDefaultToolkit();
			Image i = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			Cursor noCursor = t.createCustomCursor(i, new Point(0, 0), "none");
			JFrame frame = findJFrame(component);
			frame.setCursor(noCursor);
		} else {
			JFrame frame = findJFrame(component);
			frame.setCursor(Cursor.getDefaultCursor());
		}
	}

	protected static JFrame findJFrame (Component component) {
		Container parent = component.getParent();
		while (parent != null) {
			if (parent instanceof JFrame) {
				return (JFrame)parent;
			}
			parent = parent.getParent();
		}

		return null;
	}

	@Override
	public boolean isCursorCatched () {
		return catched;
	}

	@Override
	public int getDeltaX () {
		return lastDeltaX;
	}

	@Override
	public int getDeltaX (int pointer) {
		if(pointer < maxPointers)
			return deltaX[pointer];
		return 0;
	}

	@Override
	public int getDeltaY () {
		return lastDeltaY;
	}

	@Override
	public int getDeltaY (int pointer) {
		if(pointer < maxPointers)
			return deltaY[pointer];
		return 0;
	}

	@Override
	public void setCursorPosition (int x, int y) {
		if (robot != null) {
			robot.mouseMove(component.getLocationOnScreen().x + x, component.getLocationOnScreen().y + y);
		}
	}

	@Override
	public void setCatchMenuKey (boolean catchMenu) {
		// TODO Auto-generated method stub

	}

	@Override
	public long getCurrentEventTime () {
		return currentEventTimeStamp;
	}

	@Override
	public void getRotationMatrix (float[] matrix) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean isKeyJustPressed(int key) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCatchBackKey() {
		return false;
	}

	@Override
	public boolean isCatchMenuKey () {
		return false;
	}

	@Override
	public void setCatchKey(int keycode, boolean catchKey) {
		throw new GdxRuntimeException("Not implemented");
	}

	@Override
	public boolean isCatchKey(int keycode) {
		return false;
	}

	@Override
	public float getGyroscopeX() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public float getGyroscopeY() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public float getGyroscopeZ() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxPointers() {
		return maxPointers;
	}

	@Override
	public void addTuioObject(TuioObject tuioObject) {

	}

	@Override
	public void updateTuioObject(TuioObject tuioObject) {

	}

	@Override
	public void removeTuioObject(TuioObject tuioObject) {

	}

	@Override
	public void addTuioCursor(TuioCursor cursor) {
		int absoluteX =	(int)(cursor.getX() * screenSize.width);
		int absoluteY =	(int)(cursor.getY() * screenSize.height);
		long sessionID = cursor.getSessionID();

		Point location = component.getLocationOnScreen();
		Dimension size = component.getSize();

		// only if event hits the component
		if(absoluteX < location.x || absoluteX >= location.x + size.width
				|| absoluteY < location.y || absoluteY >= location.y + size.height)
			return;

		// avoid dialogs or contextmenus
		Window[] windows = frame.getOwnedWindows();
		for (Window window : windows) {
			if (!window.isShowing())
				continue;

			if (window instanceof Dialog && ((Dialog) window).isModal() || window.isActive() || window.isAlwaysOnTop()) {
				return;
			}
		}

		absoluteX -= location.x;
		absoluteY -= location.y;

		synchronized (this) {
			int pointer = getFreePointer();
			tuioIDToPointer.put(sessionID, pointer);
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = pointer;
			event.x = absoluteX;
			event.y = absoluteY;
			event.type = TouchEvent.TOUCH_DOWN;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[pointer] = event.x - touchX[pointer];
			deltaY[pointer] = event.y - touchY[pointer];
			touchX[pointer] = event.x;
			touchY[pointer] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			touchDown = true;
			requestRendering();
		}
	}

	@Override
	public void updateTuioCursor(TuioCursor cursor) {
		int absoluteX =	(int)(cursor.getX() * screenSize.width);
		int absoluteY =	(int)(cursor.getY() * screenSize.height);
		long sessionID = cursor.getSessionID();
		Integer pointer = tuioIDToPointer.get(sessionID);
		if (pointer == null )
			return;

		Point location = component.getLocationOnScreen();
		Dimension size = component.getSize();

		absoluteX -= location.x;
		absoluteY -= location.y;

		if(absoluteX < 0)
			absoluteX = 0;

		if(absoluteY < 0)
			absoluteY = 0;

		if(absoluteX >= size.width)
			absoluteX = size.width - 1;

		if(absoluteY >= size.height)
			absoluteY = size.height - 1;

		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = pointer;
			event.x = absoluteX;
			event.y = absoluteY;
			event.type = TouchEvent.TOUCH_DRAGGED;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[pointer] = event.x - touchX[pointer];
			deltaY[pointer] = event.y - touchY[pointer];
			touchX[pointer] = event.x;
			touchY[pointer] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			requestRendering();
		}
	}

	@Override
	public void removeTuioCursor(TuioCursor cursor) {
		int absoluteX =	(int)(cursor.getX() * screenSize.width);
		int absoluteY =	(int)(cursor.getY() * screenSize.height);
		long sessionID = cursor.getSessionID();
		Integer pointer = tuioIDToPointer.get(sessionID);
		if (pointer == null )
			return;

		Point location = component.getLocationOnScreen();
		Dimension size = component.getSize();

		absoluteX -= location.x;
		absoluteY -= location.y;

		if(absoluteX < 0)
			absoluteX = 0;

		if(absoluteY < 0)
			absoluteY = 0;

		if(absoluteX >= size.width)
			absoluteX = size.width - 1;

		if(absoluteY >= size.height)
			absoluteY = size.height - 1;

		synchronized (this) {
			TouchEvent event = usedTouchEvents.obtain();
			event.pointer = pointer;
			event.x = absoluteX;
			event.y = absoluteY;
			event.type = TouchEvent.TOUCH_UP;
			event.timeStamp = System.nanoTime();
			touchEvents.add(event);

			deltaX[pointer] = event.x - touchX[pointer];
			deltaY[pointer] = event.y - touchY[pointer];
			touchX[pointer] = event.x;
			touchY[pointer] = event.y;
			lastDeltaX = event.x - lastTouchX;
			lastDeltaY = event.y - lastDeltaY;
			lastTouchX = event.x;
			lastTouchY = event.y;
			freePointer(pointer);
			if (usedPointers.size() == 0)
				touchDown = false;

			requestRendering();
		}
	}

	@Override
	public void addTuioBlob(TuioBlob tuioBlob) {

	}

	@Override
	public void updateTuioBlob(TuioBlob tuioBlob) {

	}

	@Override
	public void removeTuioBlob(TuioBlob tuioBlob) {

	}

	@Override
	public void refresh(TuioTime tuioTime) {

	}

	static class KeyEvent {
		static final int KEY_DOWN = 0;
		static final int KEY_UP = 1;
		static final int KEY_TYPED = 2;

		long timeStamp;
		int type;
		int keyCode;
		char keyChar;
	}

	static class TouchEvent {
		static final int TOUCH_DOWN = 0;
		static final int TOUCH_UP = 1;
		static final int TOUCH_DRAGGED = 2;
		static final int TOUCH_MOVED = 3;
		static final int TOUCH_SCROLLED = 4;

		long timeStamp;
		int type;
		int x;
		int y;
		int pointer;
		int button;
		int scrollAmount;
	}
}
