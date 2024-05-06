package AOTEnvironment.planner.infrastructure;

import AOTEnvironment.planner.application.Planner;

import cetus.hir.Program;
import cetus.transforms.TransformPass;

public class CetusPlanner extends TransformPass {

    public static final String PLANNER_PASS_CMD_FLAG="aot";
    public static final String PLANNER_PASS_DESC="To start AOT environment";

    private Planner planner;
    public CetusPlanner(Program program) {
        super(program);
        this.planner = new Planner(program);
    }
    @Override
    public String getPassName() {
        return PLANNER_PASS_CMD_FLAG;
    }
    @Override
    public void start() {
       planner.plan();
    }

  
}
