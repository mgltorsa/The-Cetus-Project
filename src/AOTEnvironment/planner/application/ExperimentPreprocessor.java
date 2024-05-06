package AOTEnvironment.planner.application;

import java.util.ArrayList;
import java.util.List;

import AOTEnvironment.planner.domain.ExperimentalSection;
import cetus.analysis.LoopTools;
import cetus.hir.Annotatable;
import cetus.hir.Annotation;
import cetus.hir.AnnotationStatement;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.CodeAnnotation;
import cetus.hir.DFIterator;
import cetus.hir.Declaration;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.Identifier;
import cetus.hir.Initializer;
import cetus.hir.NameID;
import cetus.hir.PragmaAnnotation;
import cetus.hir.Program;
import cetus.hir.Specifier;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.Traversable;
import cetus.hir.UnaryExpression;
import cetus.hir.VariableDeclaration;
import cetus.hir.VariableDeclarator;
import cetus.transforms.LoopTiling;

public class ExperimentPreprocessor {

    private Program program;
    private SourceFilesReader sourceFilesReader;

    public ExperimentPreprocessor(Program program, SourceFilesReader sourceFilesReader) {
        this.program = program;
        this.sourceFilesReader = sourceFilesReader;
    }

    public String createExperimetalFileBase(ExperimentalSection experimentalSection) {
        this.sourceFilesReader.readSourceCodeDependencies();
        addLoopsInstrumentation(experimentalSection);
        return "";
    }

    private void addLoopNames(List<Statement> loops) {

        int loopId = 0;
        for (Statement stmt : loops) {
            if (!(stmt instanceof ForLoop)) {
                continue;
            }
            loopId++;
            Statement loop = (Statement) stmt;
            String my_name = "LOOP_" + loopId;
            PragmaAnnotation note = new PragmaAnnotation("loop");
            note.put("name", my_name);
            loop.annotate(note);
        }

    }

    private void addExperimentalSectionInstruments(ExperimentalSection experimentalSection) {
        List<Traversable> content = experimentalSection.getContent();

        Statement firstStmt = null;
        Statement lastStmt = null;
        for (Traversable t : content) {
            if (!(t instanceof Annotatable) || (t instanceof AnnotationStatement)) {
                continue;
            }
            if (firstStmt == null && (t instanceof ForLoop)) {
                firstStmt = (ForLoop) t;
            }

            lastStmt = (Statement) t;
        }

        firstStmt.annotateBefore(new CodeAnnotation("#ifdef SERIAL"));

        lastStmt.annotateAfter(new CodeAnnotation("#endif\n\n"));

        String[] llms = new String[] { "GPT4", "CODELLAMA" };
        String[] prompts = new String[] { "INSTRUCTIONS", "COT" };
        int[] versions = new int[] { 1, 2, 3 };

        for (String llm : llms) {
            for (String prompt : prompts) {
                for (int version : versions) {
                    String versionStr = String.format("%s_%s_%d_FULL", llm, prompt, version);
                    StringBuilder annotStr = new StringBuilder();
                    annotStr.append(String.format("#ifdef %s\n", versionStr));
                    annotStr.append(String.format(
                            "\t#ifdef CORRECTNESS\n\n\t\tstrcpy(myString, \"%s_FULL.txt\");\n\n\t#endif\n\n",
                            versionStr));

                    annotStr.append("#endif\n\n");
                    lastStmt.annotateAfter(new CodeAnnotation(annotStr.toString()));

                }
            }
        }

        String cetusAnnotStr = "#ifdef CETUS\n\n#endif\n\n";
        String plutoAnnotStr = "#ifdef PLUTO\n\n#endif\n\n";

        lastStmt.annotateAfter(new CodeAnnotation(cetusAnnotStr));
        lastStmt.annotateAfter(new CodeAnnotation(plutoAnnotStr));

    }

    private void addLoopsInstrumentation(ExperimentalSection experimentalSection) {

        List<Statement> loops = new ArrayList<>();
        for (Traversable t : experimentalSection.getContent()) {
            if (!(t instanceof ForLoop))
                continue;
            loops.add((ForLoop) t);
        }
        addLoopNames(loops);
        addExperimentalSectionInstruments(experimentalSection);
        addLoopsInstruments(loops);

    }

    private void addBaseInstrumentations(Statement stmt) {
        PragmaAnnotation loopNamePragma = stmt.getAnnotation(PragmaAnnotation.class, "name");
        if (loopNamePragma == null) {
            return;
        }
        StringBuilder initInstrument = new StringBuilder();
        StringBuilder lastInstrument = new StringBuilder();
        // struct timeval start_LOOP_1, end_LOOP_1;
        // gettimeofday(&start_LOOP_1, NULL);

        // gettimeofday(&end_LOOP_1, NULL);
        // printf("TimeInstrument_LOOP_1 = %0.8f \n", (((&end_LOOP_1)->tv_sec -
        // (&start_LOOP_1)->tv_sec) + (1.0E-6 * ((&end_LOOP_1)->tv_usec -
        // (&start_LOOP_1)->tv_usec))));

        String loopName = loopNamePragma.get("name");
        initInstrument.append(String.format("struct timeval start_%1$s, end_%1$s;\n", loopName.toUpperCase()));
        initInstrument.append(String.format("gettimeofday(&start_%s, NULL);", loopName.toUpperCase()));

        stmt.annotateBefore(new CodeAnnotation(initInstrument.toString()));

        lastInstrument.append(String.format("gettimeofday(&end_%s, NULL);\n", loopName.toUpperCase()));
        lastInstrument.append(String.format(
                "printf(\"TimeInstrument_%1$s= %%0.8f \\n\", (((&end_%1$s)->tv_sec - (&start_%1$s)->tv_sec) + (1.0E-6 * ((&end_%1$s)->tv_usec - (&start_%1$s)->tv_usec))));",
                loopName.toUpperCase()));

        // #if defined(CORRECTNESS)
        // strcpy(myString, "SERIAL_LOOP2.txt");
        // #endif

        StringBuilder correctnessInstrument = new StringBuilder();
        correctnessInstrument.append("#ifdef CORRECTNESS\n\n");
        correctnessInstrument.append(String.format("\tstrcpy(myString, \"SERIAL_%s.txt\");\n\n", loopName));
        correctnessInstrument.append("#endif\n\n");

        stmt.annotateAfter(new CodeAnnotation(correctnessInstrument.toString()));

        stmt.annotateAfter(new CodeAnnotation("#endif\n\n"));

        // CodeAnnotation variablesStoringAnnots =
        // getVariablesCheckpointAnnotation((ForLoop) stmt);
        // stmt.annotateAfter(variablesStoringAnnots);
        stmt.annotateAfter(new CodeAnnotation(lastInstrument.toString()));
    }

    private CodeAnnotation getVariablesCheckpointAnnotation(ForLoop loop) {
        DFIterator<AssignmentExpression> assignmentExpressions = new DFIterator<>(loop.getBody(),
                AssignmentExpression.class);

        List<Expression> expressionsToSave = new ArrayList<>();
        while (assignmentExpressions.hasNext()) {
            AssignmentExpression expr = assignmentExpressions.next();
            Expression rhs = expr.getLHS();
            if (rhs instanceof ArrayAccess) {
                expressionsToSave.add(rhs);
                continue;
            }
            if (rhs instanceof Identifier) {
                expressionsToSave.add(rhs);
                continue;
            }

        }
        StringBuilder annotationStr = new StringBuilder();
        annotationStr.append("#ifdef CORRECTNESS\n\n");

        for (Expression expression : expressionsToSave) {

            annotationStr.append(getCloneVariableDeclaration(expression) + ";\n\n");
        }
        annotationStr.append("#endif\n\n");

        return new CodeAnnotation(annotationStr.toString());
    }

    private String getCloneVariableDeclaration(Expression expr) {
        StringBuilder declarationBuilder = new StringBuilder();
        if (expr instanceof ArrayAccess) {
            ArrayAccess access = (ArrayAccess) expr;
            Specifier spec = getVariableSpecifier(access);
            List<Expression> indices = access.getIndices();
            String specStr = spec.toString();
            for (int i = 0; i < indices.size(); i++) {
                Expression indice = indices.get(i);
                // TODO: Create declaration
                specStr += "[" + "]";
            }
        }

        return declarationBuilder.toString();

    }

    private Specifier getVariableSpecifier(Expression expr) {
        Specifier specifier = null;
        if (expr instanceof ArrayAccess) {
            ArrayAccess access = (ArrayAccess) expr;
            List<?> specifiers = ((Identifier) access.getArrayName()).getSymbol().getTypeSpecifiers();
            if (specifiers.isEmpty()) {
                return null;
            }
            specifier = (Specifier) specifiers.get(0);
        }
        // pointers
        if (expr instanceof UnaryExpression) {
            UnaryExpression unaryExpr = (UnaryExpression) expr;
            List<Symbol> symbols = unaryExpr.getChildren().stream()
                    .filter(children -> children instanceof Identifier)
                    .map(identifier -> ((Identifier) identifier).getSymbol())
                    .toList();
            List<?> specifiers = symbols.stream().flatMap(symbol -> symbol.getTypeSpecifiers().stream()).toList();
            specifier = (Specifier) specifiers.get(0);
        }
        return specifier;
    }

    // TODO:
    // private Initializer getVariableInitializer(NameID varName, Expression expr) {
    // if (expr instanceof ArrayAccess) {
    // ArrayAccess access = (ArrayAccess) expr;
    // int dims = access.getNumIndices();
    // Specifier spec = getVariableSpecifier(expr);
    // ArrayAccess a = new ArrayAccess(access.clone(), access.getIndices());
    // AssignmentExpression assignmentExpression = new AssignmentExpression(expr,
    // null, access);
    // // Expression initExpr = new CodeAnnotation(null)
    // // Initializer init = new Initializer(null)
    // }

    // return null;
    // }

    private void addVersioningFlags(Statement stmt) {

        PragmaAnnotation loopNamePragma = stmt.getAnnotation(PragmaAnnotation.class, "name");
        if (loopNamePragma == null) {
            return;
        }

        String loopName = loopNamePragma.get("name");

        String[] llms = new String[] { "GPT4", "CODELLAMA" };
        String[] prompts = new String[] { "INSTRUCTIONS", "COT" };
        int[] versions = new int[] { 1, 2, 3 };

        String ifFlag = "#ifdef";
        for (String llm : llms) {
            for (String prompt : prompts) {
                for (int version : versions) {
                    String versionStr = String.format("%s_%s_%d_%s", llm, prompt, version, loopName);
                    StringBuilder annotStr = new StringBuilder();
                    annotStr.append(String.format("%s %s\n\n", ifFlag, versionStr));
                    ifFlag = "#elif";
                    annotStr.append(String.format(
                            "\t#ifdef CORRECTNESS\n\n\t\tstrcpy(myString, \"%s.txt\");\n\n\t#endif\n\n",
                            versionStr));
                    stmt.annotateBefore(new CodeAnnotation(annotStr.toString()));

                }
            }
        }

        stmt.annotateBefore(new CodeAnnotation("#else\n\n"));

    }

    private void addLoopsInstruments(List<Statement> stmts) {
        for (Statement stmt : stmts) {

            if (!(stmt instanceof ForLoop)) {
                continue;
            }
            addBaseInstrumentations(stmt);
            addVersioningFlags(stmt);
        }


    }
}
