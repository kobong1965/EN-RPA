/*
 * Copyright (c) 2010-2026, sikuli.org, sikulix.com, oculix-org - MIT license
 */
package org.sikuli.ide.ui;

import net.miginfocom.swing.MigLayout;
import org.sikuli.basics.PreferencesUser;
import org.sikuli.ide.ThemeAware;
import org.sikuli.support.Commons;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.sikuli.support.ide.SikuliIDEI18N._I;

/** Chinese advanced-tools welcome surface; all original actions remain available. */
public class WelcomeTab extends JPanel implements ThemeAware {
  private final ActionListener onNew;
  private final ActionListener onOpen;
  private final ActionListener onNewWorkspace;
  private final ActionListener onOpenWorkspace;
  private boolean initialized;
  private static final Color BLUE = new Color(0x0071E3);

  public WelcomeTab(ActionListener onNew, ActionListener onOpen,
                    ActionListener onNewWorkspace, ActionListener onOpenWorkspace) {
    this.onNew = onNew;
    this.onOpen = onOpen;
    this.onNewWorkspace = onNewWorkspace;
    this.onOpenWorkspace = onOpenWorkspace;
    initialized = true;
    buildUI();
  }

  private static boolean isDark() {
    Color background = UIManager.getColor("Panel.background");
    return background != null && (background.getRed() * 299
        + background.getGreen() * 587 + background.getBlue() * 114) < 128000;
  }

  private static Color foreground() {
    return isDark() ? new Color(0xF5F5F7) : new Color(0x1D1D1F);
  }

  private static Color secondary() {
    return isDark() ? new Color(0xA1A1A6) : new Color(0x6E6E73);
  }

  private static Font font(int style, int size) {
    return new Font("Microsoft YaHei UI", style, size);
  }

  private void buildUI() {
    removeAll();
    setLayout(new BorderLayout());
    Color background = isDark() ? new Color(0x1C1C1E) : new Color(0xF5F5F7);
    setBackground(background);
    JPanel page = new JPanel(new MigLayout("fillx, wrap 1, insets 38 44 28 44, gap 0",
        "[grow,fill]", ""));
    page.setBackground(background);

    JLabel eyebrow = new JLabel("OCULIX  /  高级工具");
    eyebrow.setFont(font(Font.BOLD, 11));
    eyebrow.setForeground(BLUE);
    page.add(eyebrow, "gapbottom 12");
    JLabel title = new JLabel("把操作交给执行器。");
    title.setFont(font(Font.BOLD, 30));
    title.setForeground(foreground());
    page.add(title, "gapbottom 12");
    JTextArea intro = text("在这里管理脚本、图片与工作区。准备好任务后，回到执行台运行并查看结果。", 13);
    page.add(intro, "growx, gapbottom 24");

    JPanel primary = new JPanel(new MigLayout("insets 0, gap 10", "[][]push", "[42!]"));
    primary.setOpaque(false);
    primary.add(action(_I("welcomeBtnNewScript"), "Ctrl+N", true, onNew), "growy");
    primary.add(action(_I("welcomeBtnOpenScript"), "Ctrl+O", false, onOpen), "growy");
    page.add(primary, "gapbottom 12");
    JPanel workspaces = new JPanel(new MigLayout("insets 0, gap 10", "[grow,fill][grow,fill]", "[42!]"));
    workspaces.setOpaque(false);
    workspaces.add(action(_I("welcomeBtnNewWorkspace"), "Ctrl+Shift+N", false, onNewWorkspace), "growy");
    workspaces.add(action(_I("welcomeBtnOpenWorkspace"), "Ctrl+Shift+O", false, onOpenWorkspace), "growy");
    page.add(workspaces, "gapbottom 24");

    JPanel capabilities = new JPanel(new MigLayout("wrap 1, fillx, insets 18, gap 8", "[grow,fill]", ""));
    capabilities.setBackground(isDark() ? new Color(0x2C2C2E) : Color.WHITE);
    JLabel capabilitiesTitle = new JLabel("保留完整的自动化能力");
    capabilitiesTitle.setFont(font(Font.BOLD, 12));
    capabilitiesTitle.setForeground(foreground());
    capabilities.add(capabilitiesTitle, "gapbottom 4");
    capabilities.add(text(_I("welcomeAddsVncTitle") + "  ·  " + _I("welcomeAddsVncDesc"), 12));
    capabilities.add(text(_I("welcomeAddsRecorderTitle") + "  ·  " + _I("welcomeAddsRecorderDesc"), 12));
    capabilities.add(text(_I("welcomeAddsOcrTitle") + "  ·  " + _I("welcomeAddsOcrDesc"), 12));
    page.add(capabilities, "gapbottom 24");

    JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
    footer.setOpaque(false);
    JLabel version = new JLabel("OculiX " + Commons.getSXVersionShort() + "  ·  MIT  ·  "
        + _I("welcomeFooterFork") + "    ");
    version.setFont(font(Font.PLAIN, 11));
    version.setForeground(secondary());
    footer.add(version);
    footer.add(link(_I("welcomeFooterDocs"), "https://github.com/oculix-org/Oculix/wiki"));
    footer.add(link(_I("welcomeFooterReleaseNotes"), "https://github.com/oculix-org/Oculix/releases"));
    footer.add(link(_I("welcomeFooterReportTranslation"), buildReportTranslationUrl()));
    footer.add(link("GitHub", "https://github.com/oculix-org/Oculix"));
    page.add(footer);

    JScrollPane scroll = new JScrollPane(page);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getViewport().setBackground(background);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(18);
    add(scroll, BorderLayout.CENTER);
    revalidate();
    repaint();
  }

  private static JButton action(String label, String shortcut, boolean primary, ActionListener listener) {
    JButton button = new JButton(label);
    button.setFont(font(primary ? Font.BOLD : Font.PLAIN, 13));
    button.setToolTipText(label + "（" + shortcut + "）");
    button.setMargin(new Insets(10, 18, 10, 18));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.putClientProperty("JButton.buttonType", "roundRect");
    button.setBackground(primary ? BLUE : isDark() ? new Color(0x2C2C2E) : Color.WHITE);
    button.setForeground(primary ? Color.WHITE : foreground());
    button.addActionListener(e -> { if (listener != null) listener.actionPerformed(e); });
    return button;
  }

  private static JTextArea text(String content, int size) {
    JTextArea text = new JTextArea(content);
    text.setFont(font(Font.PLAIN, size));
    text.setForeground(secondary());
    text.setOpaque(false);
    text.setEditable(false);
    text.setFocusable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setBorder(null);
    return text;
  }

  private static JButton link(String label, String url) {
    JButton button = new JButton(label);
    button.setFont(font(Font.PLAIN, 11));
    button.setForeground(BLUE);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setMargin(new Insets(2, 8, 2, 8));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.addActionListener(e -> {
      try { Desktop.getDesktop().browse(URI.create(url)); }
      catch (Exception ex) {
        JOptionPane.showMessageDialog(button, "无法打开浏览器，请访问：\n" + url,
            "打开链接", JOptionPane.INFORMATION_MESSAGE);
      }
    });
    return button;
  }

  private static String buildReportTranslationUrl() {
    Locale locale = PreferencesUser.get().getLocale();
    String tag = locale.getLanguage() + (locale.getCountry().isEmpty() ? "" : "_" + locale.getCountry());
    return "https://github.com/oculix-org/Oculix/issues/new?template=translation_issue.yml"
        + "&title=" + URLEncoder.encode("[i18n] Translation issue in " + tag, StandardCharsets.UTF_8)
        + "&locale=" + URLEncoder.encode(tag, StandardCharsets.UTF_8)
        + "&oculix-version=" + URLEncoder.encode(Commons.getSXVersionShort(), StandardCharsets.UTF_8);
  }

  @Override public void updateUI() {
    super.updateUI();
    if (initialized) buildUI();
  }
  @Override public void beforeThemeChange() { }
  @Override public void afterThemeChange() { buildUI(); }
}
