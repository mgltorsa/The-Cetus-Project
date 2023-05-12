package cetus.analysis;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.hamcrest.core.IsInstanceOf;
import cetus.codegen.ProfitableOMP;
import cetus.entities.DataRaw;
import cetus.exec.Driver;
import cetus.hir.AccessExpression;
import cetus.hir.AccessSymbol;
import cetus.hir.ArrayAccess;
import cetus.hir.ArraySpecifier;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.BooleanLiteral;
import cetus.hir.BreakStatement;
import cetus.hir.CetusAnnotation;
import cetus.hir.CharLiteral;
import cetus.hir.CommaExpression;
import cetus.hir.CommentAnnotation;
import cetus.hir.CompoundLiteral;
import cetus.hir.CompoundStatement;
import cetus.hir.ConditionalExpression;
import cetus.hir.ConstructorInitializer;
import cetus.hir.ContinueStatement;
import cetus.hir.DFIterator;
import cetus.hir.DataFlowTools;
import cetus.hir.Declaration;
import cetus.hir.DeclarationStatement;
import cetus.hir.Declarator;
import cetus.hir.DoLoop;
import cetus.hir.Expression;
import cetus.hir.ExpressionStatement;
import cetus.hir.ForLoop;
import cetus.hir.FunctionCall;
import cetus.hir.GotoStatement;
import cetus.hir.IRTools;
import cetus.hir.IfStatement;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.PointerSpecifier;
import cetus.hir.PrintTools;
import cetus.hir.Procedure;
import cetus.hir.Program;
import cetus.hir.RangeExpression;
import cetus.hir.ReturnStatement;
import cetus.hir.SimpleExpression;
import cetus.hir.StandardLibrary;
import cetus.hir.Statement;
import cetus.hir.StatementExpression;
import cetus.hir.StringLiteral;
import cetus.hir.SwitchStatement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.SymbolTools;
import cetus.hir.Symbolic;
import cetus.hir.Tools;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.hir.UnaryExpression;
import cetus.hir.UnaryOperator;
import cetus.hir.UserSpecifier;
import cetus.hir.VariableDeclaration;
import cetus.hir.VariableDeclarator;
import cetus.hir.WhileLoop;

/* import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
 */
public class DataMining extends AnalysisPass {
	// Miguel Added
	public static final String PATTERN1 = "#,##0.00;(#,##0.00)";

	// Miguel Added

	private FileWriter myWriter;

	private Logger logger = Logger.getLogger(this.getClass().getSimpleName());

	public static String LOOP_TYPE = "LOOP";
	public static String BOOLEAN_TYPE = "BOOLEAN";
	public static String CHAR_TYPE = "CHAR";
	public static String COMPUND_TYPE = "COMPUND";
	public static String INTERGER_TYPE = "INTEGER";
	public static String STRING_TYPE = "STRING";

	public static String FUNCTIONCALLS_TYPE = "FUNCTIONCALLS";
	public static String ACCESS_EXPRESSION_TYPE = "ACCESS EXPRESSION";
	public static String ARRAY_ACESS_TYPE = "ARRAY_ACESS";
	public static String ASSIGMENT_EXPRESSION_TYPE = "ASSIGMENT_EXPRESSION";
	public static String BINARY_EXPRESSION_TYPE = "BINARY_EXPRESSION_";
	public static String UNARY_EXPRESSION_TYPE = "VARIABLE_DECLARATION";
	public static String COMMA_EXPRESSION_TYPE = "COMMA_EXPRESSION";
	public static String CONDITIONAL_EXPRESSION_TYPE = "CONDITIONAL_EXPRESSION";
	public static String CONSTRUCTOR_TYPE = "CONSTRUCTOR";
	public static String DELCARATION_TYPE = "DELCARATION";
	public static String DO_LOOP_TYPE = "DO_LOOP";
	public static String EXPRESSION_TYPE = "EXPRESSION";
	public static String VARIABLE_DECLARATION_TYPE = "VARIABLE_DECLARATION";

	public static String COMPOUND_STATEMENT_TYPE = "COMPOUND_STATEMENT";
	public static String IFSTATEMENT_TYPE = "IFSTATEMENT";
	public static String DECLARATION_STATEMENT_TYPE = "DECLARATION_STATEMENT";
	public static String CONTINUES_STATEMENT_TYPE = "CONTINUES_STATEMENT";
	public static String RETURN_STATEMENT_TYPE = "RETURN_STATEMENT";
	public static String STATEMENT_EXPRESSION_TYPE = "STATEMENT_EXPRESSION_";
	public static String SWITCH_STATEMENT_TYPE = "SWITCH_STATEMENT";
	public static String EXPRESSION_STATEMENT_TYPE = "EXPRESSION_STATEMENT";

	public static Set<Symbol> pri_set;

	/* private ArrayList<AnalysisLoopTarget> loops_Features; */

	private ArrayList<Loop> all_loops = new ArrayList<Loop>();

	private List<DataRaw> listDataMining;

	private FileWriter Otherwriter;

	private int elementId;
	// Pass name
	private static final String pass_name = "[DataMining]";

	public List<DataRaw> getDataMinning() {
		return listDataMining;
	}

	public DataMining(Program program) {
		super(program);
		setLogger();
		elementId = 0;
		listDataMining = new ArrayList<DataRaw>();
		/* loops_Features = new ArrayList<AnalysisLoopTarget>(); */
		logger.info("Value, DataType ");

		/*
		 * startAnalysis(program, ((TranslationUnit)
		 * program.getChildren().get(0)).getOutputFilename());
		 */
	}

	public String getPassName() {

		return pass_name;
	}

	@Override
	public void start() {
		analysisProgram(program);
	}
	/*
	 * public void startAnalysis(Program program, String fileName) {
	 * analysisProgram(program);
	 * System.out.println("Done");
	 * }
	 */

	public void exportInformation(String information, String type) {

		logger.info(information + "," + type);

	}

	public void analysisProgram(Traversable program) {

		List<Traversable> children = program.getChildren();
		if (children != null && children.size() != 0) {

			for (int index = 0; index < children.size(); index++) {
				Traversable t = children.get(index);

				analysisProgram(t);
			}

		}

		String filename = null;
		if (program instanceof TranslationUnit) {
			filename = ((TranslationUnit) program).getInputFilename();
		}
		List<Traversable> childrenElement = program.getChildren();
		elementId++;
		String typeElement = InstanceDataType(program);
		String line_in_code = null;
		String col_in_code = null;

		int col = program.getColumn();
		int row = program.getLine();

		if (col == 0 && row == 0) {
			line_in_code = row + "";
			col_in_code = col + "";
		}

		if (program instanceof Statement) {
			line_in_code = "" + ((Statement) program).where();
		}

		DataRaw datainfo = new DataRaw(elementId, program.getParent(), program, typeElement, childrenElement);
		datainfo.setLineCode(line_in_code);
		datainfo.setFilename(filename);
		datainfo.setColumnCode(col_in_code);
		listDataMining.add(datainfo);

	}

	public String InstanceDataType(Traversable element) {
		String dataType = "";

		if (element instanceof Loop) {
			dataType += LOOP_TYPE + ";";
		}
		if (element instanceof DoLoop) {
			dataType += DO_LOOP_TYPE + ";";
		}

		if (element instanceof BooleanLiteral) {
			dataType += BOOLEAN_TYPE + ";";
		}
		if (element instanceof CharLiteral) {
			dataType += CHAR_TYPE + ";";
		}
		if (element instanceof IntegerLiteral) {
			dataType += INTERGER_TYPE + ";";
		}
		if (element instanceof StringLiteral) {
			dataType += STRING_TYPE + ";";
		}
		if (element instanceof FunctionCall) {
			dataType += FUNCTIONCALLS_TYPE + ";";
		}
		if (element instanceof AccessExpression) {
			dataType += ACCESS_EXPRESSION_TYPE + ";";
		}
		if (element instanceof ArrayAccess) {
			dataType += ARRAY_ACESS_TYPE + ";";
		}
		if (element instanceof AssignmentExpression) {
			dataType += ASSIGMENT_EXPRESSION_TYPE + ";";
		}
		if (element instanceof BinaryExpression) {
			dataType += BINARY_EXPRESSION_TYPE + ";";
		}
		if (element instanceof CommaExpression) {
			dataType += COMMA_EXPRESSION_TYPE + ";";
		}
		if (element instanceof ConditionalExpression) {
			dataType += CONDITIONAL_EXPRESSION_TYPE + ";";
		}
		if (element instanceof ConstructorInitializer) {
			dataType += CONSTRUCTOR_TYPE + ";";
		}
		if (element instanceof Declaration) {
			dataType += DELCARATION_TYPE + ";";
		}

		if (element instanceof Expression) {
			dataType += EXPRESSION_TYPE + ";";
		}
		if (element instanceof VariableDeclaration) {
			dataType += VARIABLE_DECLARATION_TYPE + ";";
		}
		if (element instanceof IfStatement) {
			dataType += IFSTATEMENT_TYPE + ";";
		}
		if (element instanceof DeclarationStatement) {
			dataType += DECLARATION_STATEMENT_TYPE + ";";
		}
		if (element instanceof ContinueStatement) {
			dataType += CONTINUES_STATEMENT_TYPE + ";";
		}
		if (element instanceof ReturnStatement) {
			dataType += RETURN_STATEMENT_TYPE + ";";
		}
		if (element instanceof StatementExpression) {
			dataType += STATEMENT_EXPRESSION_TYPE + ";";
		}
		if (element instanceof ExpressionStatement) {
			dataType += EXPRESSION_STATEMENT_TYPE + ";";
		}
		if (element instanceof SwitchStatement) {
			/* element.getClass().getSimpleName() */;
			dataType += SWITCH_STATEMENT_TYPE + ";";
		}
		if (element instanceof UnaryExpression) {
			dataType += UNARY_EXPRESSION_TYPE + ";";
		}

		if (element instanceof VariableDeclarator) {
			dataType += element.getClass().getSimpleName() + " ";
		}

		if (element instanceof VariableDeclaration) {
			dataType += element.getClass().getSimpleName() + " ";
		}

		return dataType;

	}

	private void setLogger() {

		FileHandler handler;
		try {
			handler = new FileHandler("./out/" + this.getClass().getSimpleName() + ".txt", true);

			Formatter formatter = new Formatter() {
				@Override
				public String format(LogRecord record) {
					StringBuilder sb = new StringBuilder();
					sb.append(record.getMessage()).append('\n');
					return sb.toString();
				}
			};
			handler.setFormatter(formatter);
			handler.setEncoding("utf-8");
			logger.addHandler(handler);
		} catch (SecurityException | IOException e) {
			e.printStackTrace();
		}
	}

	// Miguel Added
	public void exportInfor(Map<String, String> loopAnalysis) {
		for (Map.Entry<String, String> entry : loopAnalysis.entrySet()) {
			try {
				myWriter.write(entry.getKey() + "\n\n" + entry.getValue());
				myWriter.write("------------------------------------");

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

}
