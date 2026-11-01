package ezmj;

import javax.swing.SwingUtilities;

import ezmj.ui.ErrorReporter;
import ezmj.ui.MainFrame;

/** 程式進入點。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ErrorReporter.install();
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}
