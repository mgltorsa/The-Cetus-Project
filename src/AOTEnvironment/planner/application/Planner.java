package AOTEnvironment.planner.application;

import java.util.List;

import AOTEnvironment.planner.domain.ExperimentalSection;
import cetus.hir.Program;

public class Planner {
    private ExperimentalSectionsExtractor extractor;
    private ExperimentCreator experimentsCreator;

    public Planner(Program program) {
        this.extractor = new ExperimentalSectionsExtractor(program);
        SourceFilesReader sourceFilesReader = new SourceFilesReader(program);
        ExperimentPreprocessor preprocessor = new ExperimentPreprocessor(program, sourceFilesReader);

        ExperimentFileCreator CaRV = new ExperimentFileCreator();
        this.experimentsCreator = new ExperimentCreator(preprocessor, CaRV);
    }

    public void plan() {

        List<ExperimentalSection> experimentalSections = extractor.get();
        for (ExperimentalSection experimentalSection : experimentalSections) {
            this.experimentsCreator.createExperimentFile(experimentalSection);
        }

    }

}
