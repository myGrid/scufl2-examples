package com.example;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import uk.org.taverna.scufl2.api.annotation.Annotation;
import uk.org.taverna.scufl2.api.annotation.Revision;
import uk.org.taverna.scufl2.api.common.Child;
import uk.org.taverna.scufl2.api.common.Ported;
import uk.org.taverna.scufl2.api.common.Scufl2Tools;
import uk.org.taverna.scufl2.api.common.URITools;
import uk.org.taverna.scufl2.api.common.WorkflowBean;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.core.Processor;
import uk.org.taverna.scufl2.api.core.Workflow;
import uk.org.taverna.scufl2.api.io.ReaderException;
import uk.org.taverna.scufl2.api.io.WorkflowBundleIO;
import uk.org.taverna.scufl2.api.io.WorkflowBundleWriter;
import uk.org.taverna.scufl2.api.io.WriterException;
import uk.org.taverna.scufl2.api.port.DepthPort;
import uk.org.taverna.scufl2.api.port.GranularDepthPort;
import uk.org.taverna.scufl2.api.port.Port;
import uk.org.taverna.scufl2.api.property.PropertyLiteral;
import uk.org.taverna.scufl2.api.property.PropertyObject;
import uk.org.taverna.scufl2.api.property.PropertyResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.util.ISO8601DateFormat;

public class JsonExport {
    public class JsonWriter implements WorkflowBundleWriter {
        
        @Override
        public Set<String> getMediaTypes() {
            return new HashSet<String>(Arrays.asList("application/ld+json",
                    "application/json"));
        }
        @Override
        public void writeBundle(WorkflowBundle wfBundle, File destination,
                String mediaType) throws WriterException, IOException {
            ObjectNode json = toJson(wfBundle);
            mapper.writeValue(destination, json);
        }

        @Override
        public void writeBundle(WorkflowBundle wfBundle,
                OutputStream output, String mediaType)
                throws WriterException, IOException {
            ObjectNode json = toJson(wfBundle);
            mapper.writeValue(output, json);
        }

    }

    public static void main(String[] args) throws ReaderException, IOException,
            WriterException {
        new JsonExport().convert(args);
    }


    private WorkflowBundleIO io = new WorkflowBundleIO();;

    private WorkflowBundleWriter jsonWriter = new JsonWriter();

    private ObjectMapper mapper = new ObjectMapper();
    
    private Scufl2Tools scufl2Tools = new Scufl2Tools();
    
    private URITools uriTools = new URITools();
    
    public JsonExport() {
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mapper.setDateFormat(new ISO8601DateFormat());
        
        // Adding custom writer dynamically
        List<WorkflowBundleWriter> writers = io.getWriters();
        writers.add(jsonWriter);        
        io.setWriters(writers);
    }

    protected void addPorts(Ported ported, ObjectNode p) {
        ArrayNode inputs = mapper.createArrayNode();        
        for (Port port : ported.getInputPorts()) {
            inputs.add(toJson(port));
        }
        p.put("inputs", inputs);        
        
        ArrayNode outputs = mapper.createArrayNode();        
        for (Port port : ported.getOutputPorts()) {
            outputs.add(toJson(port));
            // FIXME: Do we need the id for ports? Needed if we add datalinks
        }
        p.put("outputs", outputs);
    }
    
    protected ObjectNode annotations(Child<?> bean) {
        ObjectNode node = mapper.createObjectNode();
        for (Annotation ann : annotationsFor(bean)) {
            URI annUri = uriTools.uriForBean(ann);
            for (PropertyObject s : ann.getBodyStatements()) {
                if (s instanceof PropertyResource) {
                    PropertyResource r = (PropertyResource) s;
                    URI resourceUri = annUri.resolve(r.getResourceURI());
                    System.out.println(resourceUri);
                    for (Entry<URI, SortedSet<PropertyObject>> entry: r.getProperties().entrySet()) {
                        URI property = annUri.resolve(entry.getKey());
                        if (entry.getValue().size() == 1) {
                            PropertyObject obj = entry.getValue().iterator().next();
                            node.put(property.toString(), toJson(obj, annUri));
                        } else {
                            ArrayNode list = mapper.createArrayNode();
                            node.put(property.toString(), list);
                            for (PropertyObject obj : entry.getValue()) {
                                list.add(toJson(obj, annUri));
                            }
                        }
                    }
                }
            }
        }
        return node;
    }

    protected List<Annotation> annotationsFor(Child<?> bean) {
        WorkflowBundle bundle = scufl2Tools.findParent(WorkflowBundle.class, bean);
        return annotationsFor(bean, bundle);
    }

    protected List<Annotation> annotationsFor(WorkflowBean bean, WorkflowBundle bundle) {
        ArrayList<Annotation> annotations = new ArrayList<Annotation>();
        if (bundle == null) {
            return annotations;
        }
        for (Annotation ann : bundle.getAnnotations()) {
            if (ann.getTarget().equals(bean)){
                annotations.add(ann);
            }
        }
        return annotations;
    }

    public void convert(String[] filepaths) throws ReaderException,
            IOException, WriterException {
        for (String filepath : filepaths) {
            File workflow = new File(filepath);

            String filename = workflow.getName();
            filename = filename.replaceFirst("\\..*", ".json");
            File workflowFile = new File(workflow.getParentFile(), filename);

            WorkflowBundle wfBundle = io.readBundle(workflow, null);
            io.writeBundle(wfBundle, workflowFile, "application/ld+json");
        }
    }

    protected ObjectNode toJson(Port port) {
       ObjectNode p = mapper.createObjectNode();
       p.put("name", port.getName());
       p.putPOJO("id", uriTools.relativeUriForBean(port, 
               scufl2Tools.findParent(WorkflowBundle.class, ((Child<?>)port))));
       
       if (port instanceof DepthPort) {
        DepthPort depthPort = (DepthPort) port;
        if (depthPort.getDepth() != null) {
            p.put("depth", depthPort.getDepth());
        }
       }
       if (port instanceof GranularDepthPort) {
           GranularDepthPort granularDepthPort = (GranularDepthPort) port;
           if (granularDepthPort.getGranularDepth() != null && 
                   ! granularDepthPort.getGranularDepth().equals(granularDepthPort.getDepth())) {
               p.put("granularDepth", granularDepthPort.getGranularDepth());
           }
       }
       p.putAll(annotations((Child<?>)port));
       return p;
    }

    protected JsonNode toJson(Processor proc) {
        ObjectNode p = mapper.createObjectNode();
        p.putPOJO("id", uriTools.relativeUriForBean(proc, proc.getParent().getParent()));
        p.put("name", proc.getName());
        addPorts(proc, p);
        p.putAll(annotations(proc));
        return p;
    }
    
    protected JsonNode toJson(PropertyObject obj, URI annUri) {
        if (obj instanceof PropertyLiteral) {
            PropertyLiteral lit = (PropertyLiteral) obj;
            return new TextNode(lit.getLiteralValue());
            // TODO: Support different literal types like integers
        }
        // TODO: Other types of annotations!
        return null;
    }

    protected JsonNode toJson(Revision currentRevision) {
        ArrayNode revisions = mapper.createArrayNode();
        while (currentRevision != null) {
            ObjectNode rev = mapper.createObjectNode();
            rev.putPOJO("id", currentRevision.getIdentifier());
            if (currentRevision.getGeneratedAtTime() != null) {
                rev.putPOJO("generatedAtTime", currentRevision.getGeneratedAtTime());
            }
            currentRevision = currentRevision.getPreviousRevision();
            if (currentRevision != null) {
                rev.putPOJO("wasRevisionOf", currentRevision.getIdentifier());
            }
            revisions.add(rev);
        }
        return revisions;
    }

    protected ObjectNode toJson(Workflow workflow) {
        ObjectNode wf = mapper.createObjectNode();

        wf.putPOJO("id", uriTools.relativeUriForBean(workflow, workflow.getParent()));
        
        wf.put("name", workflow.getName());
        wf.put("revisions", toJson(workflow.getCurrentRevision()));

        ArrayNode processors = mapper.createArrayNode();
        for (Processor p : workflow.getProcessors()) {
            processors.add(toJson(p));
        }
        addPorts(workflow, wf);
        wf.put("processors", processors);
        
        wf.putAll(annotations(workflow));
        
        return wf;
    }

    public ObjectNode toJson(WorkflowBundle wfBundle) {
        
        ObjectNode root = mapper.createObjectNode();
        ArrayNode contextList = root.arrayNode();
        root.put("@context", contextList);
        ObjectNode context = root.objectNode();
        contextList.add("https://w3id.org/scufl2/context");
        contextList.add(context);
        URI base = wfBundle.getGlobalBaseURI();
        context.put("@base", base.toASCIIString());
        root.put("id", base.toASCIIString());
       
//        root.put("name", wfBundle.getName());
//        root.put("revisions", toJson(wfBundle.getCurrentRevision()));
        
        root.put("workflow", toJson(wfBundle.getMainWorkflow()));
        
        return root;
    }
}
