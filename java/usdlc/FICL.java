package usdlc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.CharBuffer;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Random;
import java.util.regex.Pattern;

@SuppressWarnings({"NonStaticInnerClassInSecureContext",
	"PackageVisibleField","rawtypes","AutoUnboxing","UnusedDeclaration"})
public class FICL {
	private static final int RUNNING_WORD_STACK_DEPTH = 32;
	private static final int COMPILING_STACK_DEPTH = 16;
	private static final int OPERATING_STACK_DEPTH = 128;
	private static final int SECOND_STACK_DEPTH = 16;
	private static final int LOOP_STACK_DEPTH = 32;

	@SuppressWarnings("StringBufferField")
	public StringBuilder errors = new StringBuilder(64);
	public boolean abort = false, isCompileMode = false;
	public boolean throwExceptions = true;

	public FICL() { init(); }

	public boolean load(String resource) {
		InputStream stream = getClass().getResourceAsStream(resource);
		if (stream == null) {
			abort("No resource,"+resource);
			return false;
		}
		try {
			BufferedReader reader =
				new BufferedReader(new InputStreamReader(stream));
			CharBuffer buffer = CharBuffer.allocate(1024);
			reader.read(buffer);
			run(buffer.toString());
		} catch (IOException e) {
			abort(resource, e);
		}
		return abort;
	}

	/**
	 * Compile FORTH code. No execution, but a call to run afterwards will
	 * run the
	 * code just compiled.
	 */
	public boolean compile(String sourceToCompile) {
		isCompileMode = true;
		writer = new StringWriter();
		abort = false;
		source = sourceToCompile;
		sourceLength = source.length();
		sourcePointer = 0;
		compiling.clear();
		errors.setLength(0);
		String name;
		//noinspection NestedAssignment
		while (!abort && ((name = getWord()) != null)) {
			if (dictionary.containsKey(name)) {
				CompiledWord word = dictionary.get(name);
				// we call the word in compile mode
				if (word.immediate)
					word.run();
				else
					compileWord(word);
			} else if (!compileLiteral(name)) {
				abort("Unknown word: " + name);
				break;
			}
		}
		compilingWord = "[[compiled]]";
		compiled = new WordOfWords();
		isCompileMode = false;
		return !abort;
	}

	/** Run the code just compiled. */
	public boolean run() {
		debuggingCompile = false;
		return run("[[compiled]]", compiled);
	}

	/** compile and run source */
	public boolean run(String sourceCode) {
		return compile(sourceCode) && run();
	}

	boolean run(String name, Runnable actor) {
		runningWords.push(name);
		try { actor.run(); } finally {runningWords.pop();}
		return !abort;
	}

	/** Write to standard out */
	public void print(Object object) {
		String text = "";
		try {
			text = object.toString();
			writer.write(text);
		} catch (Exception e) { abort("print," + text, e); }
	}

	public void abort(String msg, Exception e) {
		if (throwExceptions) throw new RuntimeException(msg, e);
		abort(msg + ',' + e.toString());
	}

	public void abort(String msg) {
		if (isCompileMode)
			errors.append("compile,").append(compilingWord).append(',');
		else {
			errors.append("run,");
			for (int i = 1; i <= runningWords.depth; i++) {
				errors.append(runningWords.stack[i]).append("->");
			}
			errors.append(',');
		}
		errors.append(msg).append('\n');
		abort = true;
		reset();
	}

	public void reset() {
		stack.depth = secondStack.depth = 0;
		compilingStack.depth = runningWords.depth = 0;
	}

	@Override
	public String toString() {return writer.toString();}

	public String getWord() {
		// start by dropping any leading space characters
		do {
			if (sourcePointer == sourceLength) return null;
		} while (Character.isWhitespace(source.charAt(sourcePointer++)));
		int first = sourcePointer - 1;
		// and go to where we have a space again
		do {
			if (sourcePointer == sourceLength) return source.substring(first);
		} while (!Character.isWhitespace(source.charAt(sourcePointer++)));

		String word = source.substring(first, sourcePointer - 1);
		if (debuggingCompile) print(word + ' ');
		return word;
	}

	private boolean compileLiteral(String name) {
		parsePosition.setIndex(0);
		final Object value = numberParser.parse(name, parsePosition);
		if (parsePosition.getIndex() != name.length()) return false;
		compilePushWord(name, value);
		return true;
	}

	private Object fetchWordContents(String name) {
		if (!dictionary.containsKey(name)) return null;
		// call runtime to retrieve contents
		run(name, dictionary.get(name).actor);
		return stack.pop();
	}

	/**
	 * Look for a word that returns a class or instance. If not,
	 * use name to look
	 * up class directly using reflection.
	 */
	private Class fetchClass(String name) {
		Object javaClass = fetchWordContents(name);
		if (javaClass == null) {
			try {
				javaClass = Class.forName(name);
			} catch (ClassNotFoundException e) {
				abort("fetchClass," + name, e); return null;
			}
		}
		if (!(javaClass instanceof Class)) {
			javaClass = javaClass.getClass();
		}
		return (Class) javaClass;
	}

	public class Stack {
		@SuppressWarnings("InnerClassFieldHidesOuterClassField")
		Object[] stack;
		public int size = 0, depth = 0;

		public Stack(int depth) {
			stack = new Object[size = depth];
		}

		public Object peek() { return stack[depth]; }

		public Object pop() {
			Object value = stack[depth];
			depth = (depth - 1) % size;
			return value;
		}

		public void push(Object value) {
			depth = (depth + 1) % size;
			stack[depth] = value;
		}

		public int popInt() {
			Object object = pop();
			try {
				int value = 0;
				if (object instanceof Boolean) {
					value = ((Boolean)object).booleanValue() ? 1 : 0;
				} else {
					value = ((Number) object).intValue();
				}
				return value;
			} catch (Exception e) { abort("pop Integer,"+object, e); }
			return 0;
		}

		public boolean  popBoolean() { return popInt() != 0; }

		Object[] popObjectArray(int items) {
			Object[] argv = new Object[items];
			if (items > 0)
				try {
					depth -= items;
					System.arraycopy(stack, depth + 1, argv, 0, items);
				} catch (Exception e) { abort("pop parameters", e); }
			return argv;
		}

		public void pushInt(int value) { push(value); }
	}

	private void compileWord(CompiledWord word) { compiling.add(word); }

	public void compileWord(String name, Runnable word)
		{ compileWord(new CompiledWord(name, word)); }

	public void compileWord(Runnable word)
		{ compileWord(new CompiledWord(compilingWord, word)); }

	public void compilePushWord(String name, Object value) {
		compileWord(compiledPushWord(name, value));
	}

	public class CompiledWord {
		public String name, source = "";
		public Runnable actor;
		public boolean immediate = false, variable = false;
		public int order = FICL.order++;

		CompiledWord(String name, Runnable actor) {
			this.name = this.source = name;
			this.actor = actor;
		}
		public void run() {
			try {FICL.this.run(name, actor);}
			catch (Exception e) {abort("run", e);}
		}
	}
	public static int order = 0;

	private class ImmediateWord extends CompiledWord {
		ImmediateWord(String name, Runnable actor) {
			super(name, actor);
			immediate = true;
		}
	}

	private class VariableWord extends CompiledWord {
		CompiledWord value;
		VariableWord(String name, CompiledWord actor) {
			super(name, actor.actor);
			variable = true;
		}
		@Override public void run() { value.run(); }
	}

	private class WordOfWords implements Runnable {
		public final String name = compilingWord;
		private CompiledWord[] words = compiling
			.toArray(new CompiledWord[compiling.size()]);

		WordOfWords() {}

		public void run() {
			int runPointer = 0;
			int end = words.length;
			while (runPointer < end) {
				if (abort) break;
				CompiledWord word = words[runPointer++];
				//System.out.println(word.name+"("+(runPointer-1)+")");
				word.run();
				if (jumpTo != 0) runPointer = jumpTo;
				jumpTo = 0;
			}
		}
	}

	private class Method extends ImmediateWord {
		java.lang.reflect.Method method = null;
		boolean isVoidReturn = true;
		Class[] parameters = null;

		public Method() {
			super(null, null);
			try {
				String methodName = getWord();
				String className = getWord();
				name = className + '.' + methodName;

				Class javaClass = fetchClass(className);
				ArrayList<Class> pcal = new ArrayList<Class>(8);
				while (true) {
					String parameterClass = getWord();
					if (parameterClass.equals(";")) break;
					pcal.add(fetchClass(parameterClass));
				}
				parameters = pcal.toArray(new Class[pcal.size()]);
				method = javaClass.getMethod(methodName, parameters);
				isVoidReturn = method.getReturnType().equals(Void.TYPE);
				actor = new Runnable() {
					public void run() {
						try {
							Object instance = stack.pop();
							int argc = parameters.length;
							Object[] pms = stack.popObjectArray(argc);
							Object result = method.invoke(instance, pms);
							if (!isVoidReturn) stack.push(result);
						} catch (Exception e) { abort("Method,run," + name, e); }
					}
				};
			} catch (Exception e) { abort("Method,compile," + name, e); }
		}
	}

	/**
	 * Make sure further code cannot access other native instances by reflection.
	 */
	public void sandbox() {
		Iterator iterator = dictionary.entrySet().iterator();
		while (iterator.hasNext()) {
			String key = ((Entry) iterator.next()).getKey().toString();
			if ((key.length() > 1) && (key.charAt(0) == ';')) iterator.remove();
		}
	}

	private int jumpTo = 0;

	private final NumberFormat numberParser = NumberFormat.getInstance();
	private final ParsePosition parsePosition = new ParsePosition(0);

	private String source = "";
	private int sourcePointer = 0, sourceLength = 0;
	public String compilingWord = "";
	private String lastDefinition = "";
	private Stack runningWords = new Stack(RUNNING_WORD_STACK_DEPTH);
	private Collection<CompiledWord> compiling = new
		ArrayList<CompiledWord>(16);
	public boolean debuggingCompile = false;
	private Runnable compiled = null;
	private Stack compilingStack = new Stack(COMPILING_STACK_DEPTH);
	public final Stack stack = new Stack(OPERATING_STACK_DEPTH);
	private final Stack secondStack = new Stack(SECOND_STACK_DEPTH);
	private final Stack loopStack = new Stack(LOOP_STACK_DEPTH);
	private Writer writer = new StringWriter();
	public class Dictionary extends HashMap<String, CompiledWord> {
		@Override
		public CompiledWord put(final String key, final CompiledWord value) {
			boolean wasVariable = (containsKey(key) && get(key).variable);
			if (wasVariable) {
				compileWord(new Runnable() {
					public void run() {
						((VariableWord) get(key)).value = value;
					}
				});
			} else {
				super.put(key, value);
			}
			return value;
		}
	}
	public final Dictionary dictionary = new Dictionary();

	/**
	 * Extend FICL with a new word.
	 */
	public void extend(String name, Runnable actor) {
		dictionary.put(name, new CompiledWord(name, actor));
	}
	public void pushExtend(String name, final Object value) {
		dictionary.put(name, compiledPushWord(name, value));
	}
	public CompiledWord compiledPushWord(String name, final Object value) {
		return new CompiledWord(name, new Runnable() {
			public void run() {
				stack.push(value);
			}
		});
	}
	/**
	 * Extend FICL with a new word.
	 */
	public void immediate(String name, Runnable actor) {
		dictionary.put(name, new ImmediateWord(name, actor));
	}
	////////////////////////////////////////////////////////
	private void init() {
		pushExtend(";ficl", FICL.this);
		immediate(":", new Runnable() {
			public void run() {
				secondStack.push(compilingWord);
				secondStack.push(sourcePointer);
				compilingWord = getWord();
				compilingStack.push(compiling);
				compiling = new ArrayList<CompiledWord>(16);
			}
		});
		immediate(";", new Runnable() {
			public void run() {
				int start = (Integer) secondStack.pop();
				final CompiledWord compiledWord =
					new CompiledWord(compilingWord, new WordOfWords());
				compiledWord.source =
					source.substring(start, sourcePointer);

				compiling = (Collection<CompiledWord>) compilingStack.pop();
				lastDefinition = compilingWord;
				compilingWord = (String) secondStack.pop();
				dictionary.put(lastDefinition, compiledWord);
			}
		});
		extend(";call", new Runnable() {
			public void run() {
				Object compiledWord = stack.pop();
				((CompiledWord) compiledWord).run();
			}
		});
		immediate(";class", new Runnable() {
			public void run() {
				String name = getWord();
				try {
					compilePushWord(name, Class.forName(name));
				} catch (Exception e) {
					abort(";class," + name, e);
				}
			}
		});
		immediate(";field", new Runnable() {
			public void run() {
				final String fieldName = getWord();
				final String className = getWord();
				String name = className + '.' + fieldName;
				try {
					Class javaClass = fetchClass(className);
					final Field field = javaClass.getField(fieldName);
					compilePushWord(name, field);
				} catch (Exception e) {
					abort(";field," + name, e);
				}
			}
		});
		extend(";get", new Runnable() {
			public void run() {
				try {
					Field field = (Field) stack.pop();
					Object instance = stack.pop();
					stack.push(field.get(instance));
				} catch (Exception e) { abort(";get", e); }
			}
		});
		extend(";set", new Runnable() {
			public void run() {
				Field field = (Field) stack.pop();
				Object instance = stack.pop();
				Object to = stack.pop();
				try { field.set(instance, to); } catch (Exception e) {
					abort(";set," + field.toString() + ',' + to, e);
				}
			}
		});
		immediate(";method", new Runnable() {
			public void run() {
				compilePushWord(";method", new Method());
			}
		});
		immediate(";get-word", new Runnable() {
			public void run() {
				// gets it when an immediate word runs
				compileWord(";get-word", new Runnable() {
					public void run() {
						stack.push(getWord());
					}
				});
			}
		});
		immediate("(", new Runnable() {
			public void run() {
				sourcePointer = source.indexOf(')', sourcePointer) + 1;
				if (sourcePointer == 0) sourcePointer = sourceLength;
			}
		});
		immediate("\\", new Runnable() {
			public void run() {
				sourcePointer = sourceLength;
			}
		});
		immediate("\"", new Runnable() {
			public void run() {
				compilePushWord("\"", getQuotedString());
			}
		});
		immediate("%\"", new Runnable() {
			public void run() {
				final String format = getQuotedString();
				final int argc = countPlaceholders(format);
				compileWord(new Runnable() {
					public void run() {
						Object[] argv = stack.popObjectArray(argc);
						stack.push(String.format(format, argv));
					}
				});
			}
		});
		extend(".", new Runnable() {
			public void run() {
				print(stack.pop());
			}
		});
		extend("and", new Runnable() {
			public void run() {
				stack.push(stack.popInt() & stack.popInt());
			}
		});
		extend("+", new Runnable() {
			public void run() {
				stack.push(stack.popInt() + stack.popInt());
			}
		});
		immediate("begin", new Runnable() {
			public void run() {
				final int[] beginLeaveEnd = {compiling.size(), 0};
				loopStack.push(beginLeaveEnd);
			}
		});
		immediate("leave", new Runnable() {
			public void run() {
				final int[] beginLeaveEnd = (int[]) loopStack.peek();
				compileWord("leave", new Runnable() { public void run() {
					jumpTo = beginLeaveEnd[1];
				}});
			}
		});
		immediate("?leave", new Runnable() {
			public void run() {
				final int[] beginLeaveEnd = (int[]) loopStack.peek();
				compileWord("?leave", new Runnable() { public void run() {
					int testResult = ((Number) stack.pop()).intValue();
					if (testResult == 0) { jumpTo = beginLeaveEnd[1]; }
				}});
			}
		});
		immediate("again", new Runnable() {
			public void run() {
				final int[] beginLeaveEnd = (int[]) loopStack.pop();
				beginLeaveEnd[1] = compiling.size() + 1;
				compileWord("again", new Runnable() { public void run() {
					jumpTo = beginLeaveEnd[0];
				}});
			}
		});
		immediate("constant", new Runnable() {
			public void run() {
				dictionary.get(lastDefinition).run();
				final Object constant = stack.pop();
				dictionary.put(lastDefinition, compiledPushWord(
					lastDefinition + " - constant", constant));
			}
		});
		extend("dec", new Runnable() {
			public void run() {
				stack.push(stack.popInt() - 1);
			}
		});
		immediate("debug-compile", new Runnable() {
			public void run() {
				debuggingCompile = true;
			}
		});
		extend("/", new Runnable() {
			public void run() {
				stack.push(stack.popInt() / stack.popInt());
			}
		});
		extend("drop", new Runnable() {
			public void run() {
				stack.pop();
			}
		});
		extend("dup", new Runnable() {
			public void run() {
				stack.push(stack.peek());
			}
		});
		extend("=", new Runnable() {
			public void run() {
				stack.push(stack.pop().equals(stack.pop()));
			}
		});
		immediate("if", new Runnable() {
			public void run() {
				final int[] ifElseNext = {compiling.size(), 0, 0};
				secondStack.push(ifElseNext);
				compileWord("if", new Runnable() { public void run() {
					int testResult = stack.popInt();
					if (testResult == 0) { jumpTo = ifElseNext[1]; }
				}});
			}
		});
		immediate("else", new Runnable() {
			public void run() {
				final int[] ifElseNext = (int[]) secondStack.peek();
				ifElseNext[1] = compiling.size() + 1; // over else
				compileWord("else", new Runnable() { public void run() {
					jumpTo = ifElseNext[2];
				}});
			}
		});
		immediate("then", new Runnable() {
			public void run() {
				int[] ifElseNext = (int[]) secondStack.pop();
				// make sure we run the then statement to clean up secondStack
				ifElseNext[2] = compiling.size();
				if (ifElseNext[1] == 0) ifElseNext[1] = ifElseNext[2];
			}
		});
		immediate("immediate", new Runnable() {
			public void run() {
				dictionary.get(lastDefinition).immediate = true;
			}
		});
		extend("inc", new Runnable() {
			public void run() {
				stack.push(stack.popInt() + 1);
			}
		});
		extend("-", new Runnable() {
			public void run() {
				stack.push(stack.popInt() - stack.popInt());
			}
		});
		extend("*", new Runnable() {
			public void run() {
				stack.push(stack.popInt() * stack.popInt());
			}
		});
		extend("not", new Runnable() {
			public void run() {
				stack.push(stack.popInt() == 0);
			}
		});
		extend("or", new Runnable() {
			public void run() {
				stack.push(stack.popInt() | stack.popInt());
			}
		});
		immediate("push", new Runnable() {
			public void run() {
				compilePushWord("push", dictionary.get(getWord()));
			}
		});
		extend("random", new Runnable() {
			public void run() {
				stack.push(random.nextInt(stack.popInt()));
			}
		});
		extend("return", new Runnable() {
			public void run() {
				jumpTo = 100000;
			}
		});
		immediate("variable", new Runnable() {
			public void run() {
				final CompiledWord word = dictionary.get(lastDefinition);
				if (!word.variable) {
					dictionary.put(lastDefinition, new VariableWord(
						lastDefinition + " - variable", word));
					dictionary.put(lastDefinition, word);   // matches second
				}
			}
		});
		pushExtend("Code", CompiledWord.class);
		pushExtend("Integer", Integer.class);
		pushExtend("Long", Long.class);
		pushExtend("Number", Number.class);
		pushExtend("boolean", boolean.class);
		pushExtend("Object", Object.class);
	}

	private String getQuotedString() {
		int start = sourcePointer;
		sourcePointer = source.indexOf('"', sourcePointer);
		String text;
		if (sourcePointer == -1) {
			sourcePointer = sourceLength;
			text = source.substring(start);
		} else {
			text = source.substring(start, sourcePointer++);
		}
		return text;
	}
	private int countPlaceholders(String format) {
		int count = 0, start = 0;
		while ((start = format.indexOf('%', start)) != -1) {
			if (format.charAt(++start) != '%')
				count++;
			else
				start++;
		}
		return count;
	}
	private Random random = new Random();

	public boolean testBooleanCall(boolean value) {
		return value;
	}
}