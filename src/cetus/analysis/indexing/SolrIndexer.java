package cetus.analysis.indexing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;

import cetus.analysis.AnalysisPass;
import cetus.hir.DFIterator;
import cetus.hir.Loop;
import cetus.hir.Program;

public class SolrIndexer extends AnalysisPass {

    private Logger logger = Logger.getLogger(this.getClass().getSimpleName());
    private SolrClient solrClient;

    public SolrIndexer(Program program) {
        super(program);
    }

    @Override
    public String getPassName() {
        return "SolrIndexer";
    }

    public void setupClient() {
        solrClient = new Http2SolrClient.Builder("http://localhost:8983/solr/sdm")
                .build();
    }

    @Override
    public void start() {

        setupClient();

        logger.info("Starting Solr Indexer");
        List<SolrInputDocument> inDocuments = mapLoopToDocuments(getLoops(program));
        try {
            for (SolrInputDocument inDoc : inDocuments) {
                solrClient.add(inDoc);
            }

            solrClient.commit();

            Map<String, String> paramsMap = new HashMap<String, String>();
            SolrParams params = new MapSolrParams(paramsMap);

            SolrQuery query = new SolrQuery();
            query.set("q", "content:async");

            QueryResponse response = solrClient.query(params, SolrRequest.METHOD.GET);

            Spliterator<SolrDocument> results = response.getResults().spliterator();
            StreamSupport.stream(results, true).forEach((document) -> {
                logger.info("document: " + document.toString());
            });
            logger.info("Finish docs output");

        } catch (Exception e) {
            e.printStackTrace();
            logger.severe(e.getMessage());
        }

    }

    private List<Loop> getLoops(Program progam) {
        List<Loop> loops = new ArrayList<>();
        new DFIterator<Loop>(program, Loop.class).forEachRemaining(loops::add);
        return loops;
    }

    private List<SolrInputDocument> mapLoopToDocuments(List<Loop> loops) {
        List<SolrInputDocument> documents = StreamSupport
                .stream(loops.spliterator(), true)
                .map(this::mapLoopToDocument)
                .collect(Collectors.toList());
        // List<SolrInputDocument> documents = new ArrayList<>();
        return documents;
    }

    private SolrInputDocument mapLoopToDocument(Loop loop) {
        SolrInputDocument doc = new SolrInputDocument();

        doc.addField("filename", loop.getCondition().toString());
        doc.addField("content", loop.toString());
        doc.addField("constructs", doc);
        doc.addField("linecode", doc);
        doc.addField("datatypes", doc);

        return doc;
    }

}
