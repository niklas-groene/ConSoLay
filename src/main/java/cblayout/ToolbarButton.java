/*************************************************************************************
 * The Exemplary Add-on is (c) 2008-2011 Plant Bioinformatics Group, IPK Gatersleben,
 * http://bioinformatics.ipk-gatersleben.de
 * The source code for this project, which is developed by our group, is
 * available under the GPL license v2.0 available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html. By using this
 * Add-on and VANTED you need to accept the terms and conditions of this
 * license, the below stated disclaimer of warranties and the licenses of
 * the used libraries. For further details see license.txt in the root
 * folder of this project.
 ************************************************************************************/
package cblayout;

import info.clearthought.layout.TableLayout;
import org.ErrorMsg;
import org.graffiti.editor.MainFrame;
import org.graffiti.plugin.gui.GraffitiComponent;
import org.graffiti.plugin.view.View;
import org.graffiti.plugin.view.ViewListener;
import org.graffiti.session.EditorSession;
import org.graffiti.session.Session;
import org.graffiti.session.SessionListener;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyVetoException;

/**
 * A toolbar button that arranges the open graph windows side by side, one above the other or as a grid
 * ({@link WindowOrder}); it is visible when at least two windows (three for the grid) are open. Shift-click restores
 * minimised windows first, Ctrl-click minimises all windows but the active one first.
 *
 * @author Hendrik Rohn
 */
public class ToolbarButton extends JToolBar
					implements
					GraffitiComponent,
					SessionListener,
					ViewListener {
	private static final long serialVersionUID = 1L;

	private JButton arrange;
	private String prefComp;

	private WindowOrder order;

	/** A button for {@code order} in the VANTED toolbar {@code prefComp}, e.g. "defaultToolbar". */
	public ToolbarButton(String prefComp, WindowOrder order) {
		super("Arranging");
		this.prefComp = prefComp;

		this.order = order;
		arrange = new JButton(order.toString());
		arrange.setOpaque(false);
		arrange
							.setToolTipText("<html>Arranges windows.<br>"
												+ "Press Shift key and click here to restore minimized windows.<br>"
												+ "Press Ctrl key and click here to maximize the active window and to minimize all others.");

		setOpaque(false);
		double size[][] = { { 30 }, { 30 } };
		setLayout(new TableLayout(size));
		add(arrange, "0,0");

		arrange.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent actionEvent) {
				arrangeIt(actionEvent);
			}
		});

		validate();
	}

	public void sessionChanged(Session s) {
		if (s != null)
			viewChanged(s.getActiveView());
		else {
			arrange.setVisible(getVis());
		}
	}

	public void sessionDataChanged(Session s) {
		sessionChanged(s);
	}

	public void viewChanged(View newView) {
		if (newView != null) {
			arrange.setVisible(getVis());
		}
	}

	private boolean getVis() {
		switch (order) {
			case HORIZONTAL:
			case VERTICAL:
				return MainFrame.getInstance().getDesktop().getAllFrames().length > 1;
			case QUADRATIC:
				return MainFrame.getInstance().getDesktop().getAllFrames().length > 2;
		}
		return true;
	}

	public String getPreferredComponent() {
		return prefComp;
	}

	/** Arranges the windows as {@link #order} says; Shift restores and Ctrl minimises windows first. */
	private void arrangeIt(ActionEvent actionEvent) {
		for (EditorSession es : MainFrame.getEditorSessions()) {
			if (es.getGraph() != null) {
				if ((actionEvent.getModifiers() & ActionEvent.SHIFT_MASK) > 0)
					restoreFrames();
				if ((actionEvent.getModifiers() & ActionEvent.CTRL_MASK) > 0)
					iconizeFrames();

				Dimension desktopdim = MainFrame.getInstance().getDesktop()
									.getSize();

				int number = getOpenFrameCnt(), cnt = 0;

				if (number == 0)
					number = 1;

				switch (order) {
					case HORIZONTAL:
						for (JInternalFrame jf : MainFrame.getInstance()
											.getDesktop().getAllFrames()) {
							unmaximize(jf);
							jf.setBounds(desktopdim.width * (cnt++) / number,
												0, desktopdim.width / number,
												desktopdim.height);
						}
						break;
					case VERTICAL:
						for (JInternalFrame jf : MainFrame.getInstance()
											.getDesktop().getAllFrames()) {
							unmaximize(jf);
							jf.setBounds(0, desktopdim.height * (cnt++)
												/ number, desktopdim.width,
												desktopdim.height / number);
						}
						break;
					case QUADRATIC:
						int inRow = (int) Math.ceil(Math.sqrt(number));
						int rows = (int) Math.ceil((double) number
											/ (double) inRow);
						int row = 0,
						col = 0;
						for (JInternalFrame jf : MainFrame.getInstance()
											.getDesktop().getAllFrames()) {
							unmaximize(jf);
							jf.setBounds(desktopdim.width * col / inRow,
												row * desktopdim.height / rows,
												desktopdim.width / inRow, desktopdim.height
																	/ rows);
							col++;
							if (col >= inRow) {
								col = 0;
								row++;
							}
						}
						break;
				}

			}
		}
	}

	private static void unmaximize(JInternalFrame jf) {
		try {
			jf.setMaximum(false);
		} catch (PropertyVetoException e) {
			// vetoed: the frame keeps its state
		}
	}

	/** Minimises every window but the active one. */
	private void iconizeFrames() {
		JInternalFrame self = MainFrame.getInstance().getDesktop()
							.getSelectedFrame();
		if (self != null) {
			for (JInternalFrame jf : MainFrame.getInstance().getDesktop()
								.getAllFrames()) {
				if (jf != self) {
					try {
						jf.setIcon(true);
					} catch (PropertyVetoException e) {
						ErrorMsg.addErrorMessage(e);
					}
				}
			}
		}
	}

	/** The number of windows that are not minimised. */
	private int getOpenFrameCnt() {
		int res = 0;
		for (JInternalFrame jf : MainFrame.getInstance().getDesktop()
							.getAllFrames()) {
			if (!jf.isIcon())
				res++;
		}
		return res;
	}

	/**
	 * De-iconify and de-maximize all frames.
	 */
	private void restoreFrames() {

		JInternalFrame[] frames = MainFrame.getInstance().getDesktop()
							.getAllFrames();

		for (int i = 0; i < frames.length; i++)
			try {
				frames[i].setMaximum(false);
				frames[i].setIcon(false);
			} catch (PropertyVetoException e) {
				ErrorMsg.addErrorMessage(e);
			}
	}

}