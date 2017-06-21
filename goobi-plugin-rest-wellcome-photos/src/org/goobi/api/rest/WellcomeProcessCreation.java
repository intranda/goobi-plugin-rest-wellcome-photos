package org.goobi.api.rest;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.goobi.api.rest.response.WellcomeCreationProcess;
import org.goobi.api.rest.response.WellcomeCreationResponse;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.managedbeans.LoginBean;
import org.goobi.production.flow.jobs.HistoryAnalyserJob;
import org.jdom2.Namespace;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.enums.StepEditType;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.extern.log4j.Log4j;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.fileformats.mets.MetsMods;

@javax.ws.rs.Path("/wellcome")
@Log4j
public class WellcomeProcessCreation {

    private static final String XSLT = ConfigurationHelper.getInstance().getXsltFolder() + "MARC21slim2MODS3.xsl";
    private static final String MODS_MAPPING_FILE = ConfigurationHelper.getInstance().getXsltFolder() + "mods_map.xml";
    private static final Namespace MARC = Namespace.getNamespace("marc", "http://www.loc.gov/MARC21/slim");

    private Map<String, String> map = new HashMap<String, String>();

    private String currentIdentifier;
    private String currentWellcomeIdentifier;

    public WellcomeProcessCreation() {
        map.put("?Monographic", "Monograph");
        map.put("?continuing", "Periodical"); // not mapped
        map.put("?Notated music", "Monograph");
        map.put("?Manuscript notated music", "Monograph");
        map.put("?Cartographic material", "SingleMap");
        map.put("?Manuscript cartographic material", "SingleMap");
        map.put("?Projected medium", "Video");
        map.put("?Nonmusical sound recording", "Audio");
        map.put("?Musical sound recording", "Audio");
        map.put("?Two-dimensional nonprojectable graphic", "Artwork");
        map.put("?Computer file", "Monograph");
        map.put("?Kit", "Monograph");
        map.put("?Mixed materials", "Monograph");
        map.put("?Three-dimensional artefact or naturally occurring object", "3DObject");
        map.put("?Manuscript language material", "Archive");
        map.put("?BoundManuscript", "BoundManuscript");
    }

    @javax.ws.rs.Path("/create")
    @POST
    @Produces("text/xml")
    public Response createNewProcess(@HeaderParam("templateid") int templateId, @HeaderParam("hotfolder") String hotFolder) {

        Path hotFolderPath = Paths.get(hotFolder);
        if (!Files.exists(hotFolderPath) || !Files.isDirectory(hotFolderPath)) {
            Response resp = Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Hotfolder does not exist or is no directory "
                    + hotFolder)).build();
            return resp;
        }

        Process template = ProcessManager.getProcessById(templateId);
        if (template == null) {
            Response resp = Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Cannot find process template with id "
                    + templateId)).build();
            return resp;
        }

        Prefs prefs = template.getRegelsatz().getPreferences();
        List<WellcomeCreationProcess> processes = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(hotFolderPath)) {
            for (Path dir : ds) {
                List<Path> tifFiles = new ArrayList<>();
                Path csvFile = null;
                try (DirectoryStream<Path> folderFiles = Files.newDirectoryStream(dir)) {
                    for (Path file : folderFiles) {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".csv")) {
                            csvFile = file;
                        }
                        if (fileName.endsWith(".tif") || fileName.endsWith(".tiff")) {
                            tifFiles.add(file);
                        }
                    }
                }
                try {
                    WellcomeCreationProcess wcp = createProcess(csvFile, tifFiles, prefs, template);
                    if (wcp == null) {
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(createErrorResponse("Cannot import csv file: "
                                + csvFile))
                                .build();
                    }
                    processes.add(wcp);
                } catch (Exception e) {
                    //TODO: this should be collected and be returned as one at the end
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(createErrorResponse("Cannot import csv file: " + csvFile))
                            .build();
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        }

        WellcomeCreationResponse resp = new WellcomeCreationResponse();
        resp.setProcesses(processes);
        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();
    }

    private WellcomeCreationProcess createProcess(Path csvFile, List<Path> tifFiles, Prefs prefs, Process template) throws Exception {
        CSVUtil csv = new CSVUtil(csvFile);
        String referenceNumber = csv.getValue("Reference", 0);
        Fileformat ff = convertData(csv, tifFiles, prefs);
        if (ff == null) {
            return null;
        }
        Process process = cloneTemplate(template);
        // set title
        process.setTitel(referenceNumber);//TODO remove whitespaces here?

        NeuenProzessAnlegen(process, template, ff, prefs);

        saveProperty(process, "b-number", referenceNumber); //TODO remove whitespaces here?
        saveProperty(process, "CollectionName1", "Editorial Photography"); //TODO
        saveProperty(process, "CollectionName2", referenceNumber); //TODO
        saveProperty(process, "securityTag", "open");
        saveProperty(process, "schemaName", "Millennium");

        WellcomeCreationProcess wcp = new WellcomeCreationProcess();
        wcp.setProcessId(process.getId());
        wcp.setProcessName(process.getTitel());
        return wcp;
    }

    private void generateDefaultValues(Prefs prefs, String collectionName, DocStruct dsRoot, DocStruct dsBoundBook)
            throws MetadataTypeNotAllowedException {

        // Add 'pathimagefiles'
        try {
            Metadata mdForPath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            mdForPath.setValue("./" + currentIdentifier);
            dsBoundBook.addMetadata(mdForPath);
        } catch (MetadataTypeNotAllowedException e1) {
            log.error("MetadataTypeNotAllowedException while reading images", e1);
        } catch (DocStructHasNoTypeException e1) {
            log.error("DocStructHasNoTypeException while reading images", e1);
        }

        MetadataType mdTypeCollection = prefs.getMetadataTypeByName("singleDigCollection");

        Metadata mdCollection = new Metadata(mdTypeCollection);
        mdCollection.setValue(collectionName);
        dsRoot.addMetadata(mdCollection);

        Metadata dateDigitization = new Metadata(prefs.getMetadataTypeByName("_dateDigitization"));
        dateDigitization.setValue("2012");
        Metadata placeOfElectronicOrigin = new Metadata(prefs.getMetadataTypeByName("_placeOfElectronicOrigin"));
        placeOfElectronicOrigin.setValue("Wellcome Trust");
        Metadata _electronicEdition = new Metadata(prefs.getMetadataTypeByName("_electronicEdition"));
        _electronicEdition.setValue("[Electronic ed.]");
        Metadata _electronicPublisher = new Metadata(prefs.getMetadataTypeByName("_electronicPublisher"));
        _electronicPublisher.setValue("Wellcome Trust");
        Metadata _digitalOrigin = new Metadata(prefs.getMetadataTypeByName("_digitalOrigin"));
        _digitalOrigin.setValue("reformatted digital");
        if (dsRoot.getType().isAnchor()) {
            DocStruct ds = dsRoot.getAllChildren().get(0);
            ds.addMetadata(dateDigitization);
            ds.addMetadata(_electronicEdition);

        } else {
            dsRoot.addMetadata(dateDigitization);
            dsRoot.addMetadata(_electronicEdition);
        }
        dsRoot.addMetadata(placeOfElectronicOrigin);
        dsRoot.addMetadata(_electronicPublisher);
        dsRoot.addMetadata(_digitalOrigin);

        Metadata physicalLocation = new Metadata(prefs.getMetadataTypeByName("_digitalOrigin"));
        physicalLocation.setValue("Wellcome Trust");
        dsBoundBook.addMetadata(physicalLocation);
    }

    private Fileformat convertData(CSVUtil csv, List<Path> tifFiles, Prefs prefs) {
        Fileformat ff = null;
        try {

            ff = new MetsMods(prefs);
            DigitalDocument dd = new DigitalDocument();
            ff.setDigitalDocument(dd);

            // Determine the root docstruct type
            String dsType = "EditorialPhotography";

            DocStruct dsRoot = dd.createDocStruct(prefs.getDocStrctTypeByName(dsType));

            Metadata md = new Metadata(prefs.getMetadataTypeByName("TitleDocMain"));
            md.setValue(csv.getValue("Title"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("ShootType"));
            md.setValue(csv.getValue("Shoot Type"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("CatalogIdDigital"));
            md.setValue(csv.getValue("Reference"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("PlaceOfPublication"));
            md.setValue(csv.getValue("Location"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("Contains"));
            md.setValue(csv.getValue("Caption"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("People"));
            md.setValue(csv.getValue("People"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("Description"));
            md.setValue(csv.getValue("Keywords"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("Usage"));
            md.setValue(csv.getValue("Intended Usage"));
            dsRoot.addMetadata(md);
            md = new Metadata(prefs.getMetadataTypeByName("AccessLicense"));
            md.setValue(csv.getValue("Usage Terms"));
            dsRoot.addMetadata(md);

            Person p = new Person(prefs.getMetadataTypeByName("Creator"));
            p.setFirstname("fn");
            p.setLastname("ls");
            dsRoot.addPerson(p);

            dd.setLogicalDocStruct(dsRoot);

            DocStruct dsBoundBook = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            //TODO add files to dsBoundBook
            dd.setPhysicalDocStruct(dsBoundBook);

            // Collect MODS metadata

            // Add dummy volume to anchors ??
            //            generateDefaultValues(prefs, collectionName, dsRoot, dsBoundBook);

        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException e) {
            log.error(e);
        }
        return ff;
    }

    private WellcomeCreationResponse createErrorResponse(String errorText) {
        WellcomeCreationResponse resp = new WellcomeCreationResponse();
        resp.setResult("error");
        resp.setErrorText(errorText);
        return resp;
    }

    private Process cloneTemplate(Process template) {
        Process process = new Process();

        process.setIstTemplate(false);
        process.setInAuswahllisteAnzeigen(false);
        process.setProjekt(template.getProjekt());
        process.setRegelsatz(template.getRegelsatz());
        process.setDocket(template.getDocket());

        BeanHelper bHelper = new BeanHelper();
        bHelper.SchritteKopieren(template, process);
        bHelper.ScanvorlagenKopieren(template, process);
        bHelper.WerkstueckeKopieren(template, process);
        bHelper.EigenschaftenKopieren(template, process);

        return process;
    }

    public void NeuenProzessAnlegen(Process process, Process template, Fileformat ff, Prefs prefs) throws Exception {

        for (Step step : process.getSchritteList()) {

            step.setBearbeitungszeitpunkt(process.getErstellungsdatum());
            step.setEditTypeEnum(StepEditType.AUTOMATIC);
            LoginBean loginForm = (LoginBean) Helper.getManagedBeanValue("#{LoginForm}");
            if (loginForm != null) {
                step.setBearbeitungsbenutzer(loginForm.getMyBenutzer());
            }

            if (step.getBearbeitungsstatusEnum() == StepStatus.DONE) {
                step.setBearbeitungsbeginn(process.getErstellungsdatum());

                Date myDate = new Date();
                step.setBearbeitungszeitpunkt(myDate);
                step.setBearbeitungsende(myDate);
            }

        }

        ProcessManager.saveProcess(process);

        /*
         * -------------------------------- Imagepfad hinzufügen (evtl. vorhandene zunächst löschen) --------------------------------
         */
        try {
            MetadataType mdt = prefs.getMetadataTypeByName("pathimagefiles");
            List<? extends Metadata> alleImagepfade = ff.getDigitalDocument().getPhysicalDocStruct().getAllMetadataByType(mdt);
            if (alleImagepfade != null && alleImagepfade.size() > 0) {
                for (Metadata md : alleImagepfade) {
                    ff.getDigitalDocument().getPhysicalDocStruct().getAllMetadata().remove(md);
                }
            }
            Metadata newmd = new Metadata(mdt);
            if (SystemUtils.IS_OS_WINDOWS) {
                newmd.setValue("file:/" + process.getImagesDirectory() + process.getTitel().trim() + "_tif");
            } else {
                newmd.setValue("file://" + process.getImagesDirectory() + process.getTitel().trim() + "_tif");
            }
            ff.getDigitalDocument().getPhysicalDocStruct().addMetadata(newmd);

            /* Rdf-File schreiben */
            process.writeMetadataFile(ff);

        } catch (ugh.exceptions.DocStructHasNoTypeException | MetadataTypeNotAllowedException e) {
            log.error(e);
        }

        // Adding process to history
        HistoryAnalyserJob.updateHistoryForProzess(process);

        ProcessManager.saveProcess(process);

        process.readMetadataFile();

        List<Step> steps = StepManager.getStepsForProcess(process.getId());
        for (Step s : steps) {
            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                myThread.start();
            }
        }
    }

    private void saveProperty(Process process, String name, String value) {
        Processproperty pe = new Processproperty();
        pe.setTitel(name);
        pe.setType(PropertyType.String);
        pe.setWert(value);
        pe.setProzess(process);
        PropertyManager.saveProcessProperty(pe);
    }

    public String getProcessTitle() {
        if (currentWellcomeIdentifier != null) {
            String temp = currentWellcomeIdentifier.replaceAll("\\W", "_");
            if (StringUtils.isNotBlank(temp)) {
                return temp.toLowerCase() + "_" + currentIdentifier;
            }
        }
        return currentIdentifier;
    }
}
