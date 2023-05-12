package cetus.analysis.indexing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
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
import cetus.analysis.DataMining;
import cetus.entities.DataRaw;
import cetus.hir.Program;

public class SolrIndexer extends AnalysisPass {

    private Logger logger = Logger.getLogger(this.getClass().getSimpleName());
    private SolrClient solrClient;
    private DataMining crawler;

    public SolrIndexer(Program program) {
        super(program);
        crawler = new DataMining(program);
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

        crawler.start();

        logger.info("Starting Solr Indexer");
        List<SolrInputDocument> inDocuments = mapDataRawToDocuments(filterCodeBlocks(crawler.getDataMinning()));
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

    private boolean containsBlockType(DataRaw datainfo) {
        // WARNING: It is already done in data mining.java
        return datainfo.getTypeValue().contains(DataMining.LOOP_TYPE)
                || datainfo.getTypeValue().contains(DataMining.DO_LOOP_TYPE)
                || datainfo.getTypeValue().contains(DataMining.COMPOUND_STATEMENT_TYPE)
                || datainfo.getTypeValue().contains(DataMining.COMPUND_TYPE);
        // return true;
    }

    private List<DataRaw> filterCodeBlocks(List<DataRaw> dataMinning) {
        return StreamSupport.stream(dataMinning.spliterator(), true).filter(this::containsBlockType)
                .collect(Collectors.toList());
    }

    private List<SolrInputDocument> mapDataRawToDocuments(List<DataRaw> dataMinning) {
        return StreamSupport
                .stream(dataMinning.spliterator(), true)
                .map(this::mapLoopToDocument)
                .collect(Collectors.toList());
    }

    private SolrInputDocument mapLoopToDocument(DataRaw dataRaw) {
        SolrInputDocument doc = new SolrInputDocument();

        doc.addField("filename", dataRaw.getFilename());

        doc.addField("content", dataRaw.getValue().toString());

        if (dataRaw.getLineCode() != null) {
            doc.addField("linecode", dataRaw.getLineCode());
        }

        if (dataRaw.getColumnCode() != null) {
            doc.addField("columncode", dataRaw.getColumnCode());
        }

        String parentId = dataRaw.getParent().getFilename();
        boolean hasLineCode = false;
        if (!dataRaw.getParent().getLineCode().isBlank()) {
            parentId += "-" + dataRaw.getParent().getLineCode();
            hasLineCode = true;
        }

        if (hasLineCode && !dataRaw.getParent().getColumnCode().isBlank()) {
            parentId += "-" + dataRaw.getParent().getColumnCode();
        }

        if (!hasLineCode) {
            parentId = ":" + dataRaw.getId();
        }

        doc.addField("parent_id", parentId);

        doc.addField("constructs", dataRaw.getTypeValue());
        doc.addField("datatypes", dataRaw.getTypeValue().replaceAll(";", " "));

        return doc;
    }

}
