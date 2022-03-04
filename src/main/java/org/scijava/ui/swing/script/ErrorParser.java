/*
 * #%L
 * Script Editor and Interpreter for SciJava script languages.
 * %%
 * Copyright (C) 2009 - 2022 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.ui.swing.script;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.Parser;
import org.scijava.script.ScriptLanguage;

public class ErrorParser {

	/* Color for ErrorStrip marks and fallback taint for line highlights */
	private static final Color COLOR = Color.RED;
	/* 0-base indices of Editor's lines that have errored */
	private TreeSet<Integer> errorLines;
	/* When running selected code errored lines map to Editor through this offset */
	private int lineOffset;
	private boolean enabled;
	private final EditorPane editorPane;
	private JTextAreaWriter writer;
	private int lengthOfJTextAreaWriter;
	private ErrorStripNotifyingParser notifyingParser;

	public ErrorParser(final EditorPane editorPane) {
		this.editorPane = editorPane;
		lineOffset = 0;
	}

	public void gotoPreviousError() {
		if (!isCaretMovable()) {
			try {
				final int caretLine = editorPane.getCaretLineNumber();
				gotoLine(errorLines.lower(caretLine));
			} catch (final NullPointerException ignored) {
				gotoLine(errorLines.last());
			}
		}
	}

	public void gotoNextError() {
		if (isCaretMovable()) {
			try {
				final int caretLine = editorPane.getCaretLineNumber();
				gotoLine(errorLines.higher(caretLine));
			} catch (final NullPointerException ignored) {
				gotoLine(errorLines.first());
			}
		}
	}

	public void reset() {
		if (notifyingParser != null) {
			editorPane.removeParser(notifyingParser);
			if (notifyingParser.highlightAbnoxiously)
				editorPane.removeAllLineHighlights();
		}
	}

	public void setEnabled(final boolean enabled) {
		this.enabled = enabled;
	}

	public void setLineOffset(final int zeroBasedlineOffset) {
		this.lineOffset = zeroBasedlineOffset;
	}

	public void setSelectedCodeExecution(final boolean selectedExecution) {
		if (selectedExecution) {
			final int p0 = Math.min(editorPane.getCaret().getDot(), editorPane.getCaret().getMark());
			final int p1 = Math.max(editorPane.getCaret().getDot(), editorPane.getCaret().getMark());
			if (p0 != p1) {
				try {
					lineOffset = editorPane.getLineOfOffset(p0);
				} catch (final BadLocationException ignored) {
					lineOffset = -1;
				}
			} else {
				lineOffset = -1;
			}
		} else {
			lineOffset = 0;
		}
	}

	public void setWriter(final JTextAreaWriter writer) {
		this.writer = writer;
		lengthOfJTextAreaWriter = writer.textArea.getText().length();
	}

	public void parse() {
		if (writer == null)
			throw new IllegalArgumentException("Writer is null");
		parse(writer.textArea.getText().substring(lengthOfJTextAreaWriter));
	}

	public void parse(final Throwable t) {
		if (!enabled)
			return;
		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		parse(sw.toString());
	}

	private boolean isCaretMovable() {
		if (errorLines == null || errorLines.isEmpty() || !editorPane.isEditable() || !editorPane.isEnabled()) {
			UIManager.getLookAndFeel().provideErrorFeedback(editorPane);
			return false;
		}
		return true;
	}

	private void gotoLine(final int lineNumber) {
		try {
			editorPane.setCaretPosition(editorPane.getLineStartOffset(lineNumber));
			// ensure line is visible. Probably not needed!?
			final Rectangle rect = editorPane.modelToView(editorPane.getCaretPosition());
			editorPane.scrollRectToVisible(rect);
		} catch (final BadLocationException ignored) {
			// do nothing
		} finally {
			editorPane.requestFocusInWindow();
		}
	}

	private void parse(final String errorLog) {

		final ScriptLanguage lang = editorPane.getCurrentLanguage();
		if (lang == null)
			return;
	
		// Do nothing if disabled, or if only selected text was evaluated in the
		// script but we don't know where in the document such selection occurred
		if (!enabled) {
			abort("Execution errors are not highlighted when auto-imports are active", true);
			return;
		}
		if (lineOffset == -1) {
			abort("Code selection unknown: Erros are not highlighted in the Editor", true);
			return;
		}
	
		final boolean isJava = "Java".equals(lang.getLanguageName());
		final String fileName = editorPane.getFileName();
		if (isJava && fileName == null)
			return;

		// HACK scala code seems to always be pre-pended by some 10 lines of code(!?).
		if ("Scala".equals(lang.getLanguageName()))
			lineOffset += 10;
		// HACK and R by one (!?)
		else if ("R".equals(lang.getLanguageName()))	
			lineOffset += 1;

		errorLines = new TreeSet<>();
		final StringTokenizer tokenizer = new StringTokenizer(errorLog, "\n");
		if (isJava) {
			while (tokenizer.hasMoreTokens()) {
				parseJava(fileName, tokenizer.nextToken(), errorLines);
			}
		} else {
			while (tokenizer.hasMoreTokens()) {
				parseNonJava(tokenizer.nextToken(), errorLines);
			}
		}
		if (errorLines.isEmpty() && "IJ1 Macro".equals(lang.getLanguageName())) {
			abort("Execution errors handled by the Macro Interpreter. Use the Interpreter's Debug option for error tracking", false);
		} else if (!errorLines.isEmpty()) {
			notifyingParser = new ErrorStripNotifyingParser();
			editorPane.addParser(notifyingParser);
			editorPane.forceReparsing(notifyingParser);
			gotoLine(errorLines.first());
		}
	}

	private void parseNonJava(final String lineText, final Collection<Integer> errorLines) {

		if ( // Elimination of some false positives. TODO: Make this Regex
			lineText.indexOf(":classloader:") > -1 // ruby
			|| lineText.indexOf(".org.python.") > -1 // python
			|| lineText.indexOf(".codehaus.groovy.") > -1 // groovy
			|| lineText.indexOf(".tools.nsc.") > -1 // scala
			|| lineText.indexOf("at bsh.") > -1 // beanshel
			|| lineText.indexOf("$Recompilation$") > -1 // javascript
			|| lineText.indexOf("at org.renjin.") > -1 // R: The only thing useful in traces are Syntax errors!?
		) {//
			return;
		}

		final int extensionIdx = extensionIdx(lineText);
		final int lineIdx = lineText.toLowerCase().indexOf("line");
		if (lineIdx < 0 && extensionIdx < 0 && filenameIdx(lineText) < 0)
			return;

		extractLineIndicesFromFilteredTextLines(lineText, errorLines);
	}

	private void extractLineIndicesFromFilteredTextLines(final String lineText, final Collection<Integer> errorLines) {
//		System.out.println("  Section being matched: " + lineText);
		final Pattern pattern = Pattern.compile(":(\\d+)|line\\D*(\\d+)", Pattern.CASE_INSENSITIVE);
		final Matcher matcher = pattern.matcher(lineText);

		if (matcher.find()) {
			try {
				final String firstGroup = matcher.group(1);
				final String lastGroup = matcher.group(matcher.groupCount());
				final String group = (firstGroup == null) ? lastGroup : firstGroup;
//				System.out.println("  firstGroup: " + firstGroup);
//				System.out.println("  lastGroup: " + lastGroup);

				final int lineNumber = Integer.valueOf(group.trim());
				if (lineNumber > 0)
					errorLines.add(lineNumber - 1 + lineOffset); // store 0-based indices
			} catch (final NumberFormatException e) {
				e.printStackTrace();
			}
		}
	}

	private int extensionIdx(final String line) {
		int dotIndex = -1;
		for (final String extension : editorPane.getCurrentLanguage().getExtensions()) {
			dotIndex = line.indexOf("." + extension);
			if (dotIndex > -1)
				return dotIndex;
		}
		return -1;
	}

	private int filenameIdx(final String line) {
		int index = line.indexOf(editorPane.getFileName());
		if (index == -1)
			index = (line.indexOf(" Script")); // unsaved file, etc.
		return index;
	}

	private void parseJava(final String filename, final String line, final Collection<Integer> errorLines) {
		int colon = line.indexOf(filename);
		if (colon <= 0)
			return;
//		System.out.println("Parsing candidate: " + line);
		colon += filename.length();
		final int next = line.indexOf(':', colon + 1);
		if (next < colon + 2)
			return;
		try {
			final int lineNumber = Integer.parseInt(line.substring(colon + 1, next));
			if (lineNumber > 0)
				errorLines.add(lineNumber - 1 + lineOffset); // store 0-based indices
//			System.out.println("line Np: " + (lineNumber - 1));
		} catch (final NumberFormatException e) {
			// ignore
		}
	}

	private void abort(final String msg, final boolean offsetNotice) {
		if (writer != null) {
			String finalMsg = "[WARNING] " + msg + "\n";
			if (offsetNotice)
				finalMsg += "[WARNING] Error line(s) below may not match line numbers in the editor\n";
			writer.textArea.insert(finalMsg, lengthOfJTextAreaWriter);
		}
		errorLines = null;
	}

	/**
	 * This is just so that we can register errorLines in the Editor's
	 * {@link org.fife.ui.rsyntaxtextarea.ErrorStrip}
	 */
	class ErrorStripNotifyingParser extends AbstractParser {

		private final DefaultParseResult result;
		private final boolean highlightAbnoxiously = true;

		public ErrorStripNotifyingParser() {
			result = new DefaultParseResult(this);
		}

		@Override
		public boolean isEnabled() {
			return enabled && errorLines != null;
		}

		@Override
		public ParseResult parse(final RSyntaxDocument doc, final String style) {
			final Element root = doc.getDefaultRootElement();
			final int lineCount = root.getElementCount();
			result.clearNotices();
			result.setParsedLines(0, lineCount - 1);
			if (isEnabled() && !SyntaxConstants.SYNTAX_STYLE_NONE.equals(style)) {
				errorLines.forEach(line -> {
					result.addNotice(new ErrorNotice(this, line));
				});
				if (highlightAbnoxiously) {
					final Color c = highlightColor();
					errorLines.forEach(line -> {
						try {
							editorPane.addLineHighlight(line, c);
						} catch (final BadLocationException ignored) {
							// do nothing
						}
					});
				}
			}
			return result;

		}

	}

	private Color highlightColor() {
		// https://stackoverflow.com/a/29576746
		final Color c1 = editorPane.getCurrentLineHighlightColor();
		final Color c2 = (editorPane.getBackground() == null) ? COLOR : editorPane.getBackground();
		final int r = (int) Math.sqrt( (Math.pow(c1.getRed(), 2) + Math.pow(c2.getRed(), 2)) / 2);
		final int g = (int) Math.sqrt( (Math.pow(c1.getGreen(), 2) + Math.pow(c2.getGreen(), 2)) / 2);
		final int b = (int) Math.sqrt( (Math.pow(c1.getBlue(), 2) + Math.pow(c2.getGreen(), 2)) / 2);
		return new Color(r, g, b, c1.getAlpha());
	}

	class ErrorNotice extends DefaultParserNotice {
		public ErrorNotice(final Parser parser, final int line) {
			super(parser, "Run Error: Line " + (line + 1), line);
			setColor(COLOR);
			setLevel(Level.ERROR);
			setShowInEditor(true);
		}

	}

	public static void main(final String[] args) throws Exception {
		// poor man's test for REGEX filtering
		final String groovy = "	at Script1.run(Script1.groovy:51)";
		final String python = "File \"New_.py\", line 51, in <module>";
		final String ruby = "<main> at Batch_Convert.rb:51";
		final String scala = " at line number 51 at column number 18";
		final String beanshell = "or class name: Dummy : at Line: 51 : in file: ";
		final String javascript = "	at jdk.nashorn.internal.scripts.Script$15$Greeting.:program(Greeting.js:51)";
		final String r = "org.renjin.parser.ParseException: Syntax error at line 51 char 2: syntax error, unexpected ',', expecting '\\n' or ';'";
		Arrays.asList(groovy, python, ruby, scala, beanshell, javascript, r).forEach(lang -> {
			final ErrorParser parser = new ErrorParser(new EditorPane());
			final TreeSet<Integer> errorLines = new TreeSet<>();
			parser.extractLineIndicesFromFilteredTextLines(lang, errorLines);
			assert (errorLines.first() == 50);
			System.out.println((errorLines.first() == 50) + ": " + lang);
		});
	}
}
