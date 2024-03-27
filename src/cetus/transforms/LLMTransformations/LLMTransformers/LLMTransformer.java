package cetus.transforms.LLMTransformations.LLMTransformers;

public interface LLMTransformer {
    public LLMResponse transform(String prompt, String programSection, BasicModelParameters parameters)
            throws Exception;

    public String getModel();
}
