package org.goobi.api.rest;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.goobi.api.rest.response.WellcomeEditorialCreationProcess;
import org.goobi.api.rest.response.WellcomeEditorialCreationResponse;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.managedbeans.LoginBean;
import org.goobi.production.flow.jobs.HistoryAnalyserJob;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;

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
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.fileformats.mets.MetsMods;

@javax.ws.rs.Path("/wellcome")
@Log4j
public class WellcomeEditorialProcessCreation {

    private String currentIdentifier;
    private String currentWellcomeIdentifier;
    private static final long FIVEMINUTES = 1000 * 60 * 5;

    private static final Namespace metsNs = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace xlinkNs = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    public static XPathFactory xFactory = XPathFactory.instance();
    public static XPathExpression<Element> masterXpath = xFactory.compile("//mets:fileGrp[@USE='MASTER']/mets:file", Filters.element(), null, metsNs);

    @javax.ws.rs.Path("/createeditorials")
    @POST
    @Produces("text/xml")
    public Response createNewProcess(@HeaderParam("templateid") int templateNewId, @HeaderParam("updatetemplateid") int templateUpdateId,
            @HeaderParam("hotfolder") String hotFolder) {

        Path hotFolderPath = Paths.get(hotFolder);
        if (!Files.exists(hotFolderPath) || !Files.isDirectory(hotFolderPath)) {
            Response resp = Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Hotfolder does not exist or is no directory "
                    + hotFolder)).build();
            return resp;
        }

        Process templateUpdate = ProcessManager.getProcessById(templateUpdateId);
        Process templateNew = ProcessManager.getProcessById(templateNewId);
        if (templateNew == null) {
            Response resp = Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Cannot find process template with id "
                    + templateNewId)).build();
            return resp;
        }

        Prefs prefs = templateNew.getRegelsatz().getPreferences();
        List<WellcomeEditorialCreationProcess> processes = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(hotFolderPath)) {
            for (Path dir : ds) {
                log.debug("working with folder " + dir.getFileName());
                if (!checkIfCopyingDone(dir)) {
                    continue;
                }
                Path lockFile = dir.resolve(".intranda_lock");
                if (Files.exists(lockFile)) {
                    continue;
                }
                try (OutputStream os = Files.newOutputStream(lockFile)) {
                }
                List<Path> tifFiles = new ArrayList<>();
                Path csvFile = null;
                try (DirectoryStream<Path> folderFiles = Files.newDirectoryStream(dir)) {
                    for (Path file : folderFiles) {
                        String fileName = file.getFileName().toString();
                        String fileNameLower = fileName.toLowerCase();
                        if (fileNameLower.endsWith(".csv") && !fileNameLower.startsWith(".")) {
                            csvFile = file;
                        }
                        if ((fileNameLower.endsWith(".tif") || fileNameLower.endsWith(".tiff") || fileNameLower.endsWith(".mp4"))
                                && !fileNameLower.startsWith(".")) {
                            tifFiles.add(file);
                        }
                    }
                }
                Collections.sort(tifFiles);
                try {
                    WellcomeEditorialCreationProcess wcp = createProcess(csvFile, tifFiles, prefs, templateNew, templateUpdate);
                    if (wcp == null) {
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(createErrorResponse("Cannot import csv file: "
                                + csvFile)).build();
                    }
                    processes.add(wcp);
                    if (wcp.getProcessName() == null) {
                        // we wait for the old process to be copied to s3 first
                        Files.deleteIfExists(lockFile);
                    } else {
                        // process created. Now delete this folder.
                        FileUtils.deleteQuietly(dir.toFile());
                    }
                } catch (Exception e) {
                    //TODO: this should be collected and be returned as one at the end
                    log.error(e);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(createErrorResponse("Cannot import csv file: " + csvFile))
                            .build();
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            log.error(e);
        }

        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
        resp.setProcesses(processes);
        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();
    }

    private boolean checkIfCopyingDone(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        Date now = new Date();
        FileTime dirAccessTime = Files.readAttributes(dir, BasicFileAttributes.class).lastModifiedTime();
        log.debug("now: " + now + " dirAccessTime: " + dirAccessTime);
        long smallestDifference = now.getTime() - dirAccessTime.toMillis();
        int fileCount = 0;
        try (DirectoryStream<Path> folderFiles = Files.newDirectoryStream(dir)) {
            for (Path file : folderFiles) {
                fileCount++;
                FileTime fileAccessTime = Files.readAttributes(file, BasicFileAttributes.class).lastModifiedTime();
                log.debug("now: " + now + " fileAccessTime: " + fileAccessTime);
                long diff = now.getTime() - fileAccessTime.toMillis();
                if (diff < smallestDifference) {
                    smallestDifference = diff;
                }
            }
        }
        return (FIVEMINUTES < smallestDifference) && fileCount > 0;
    }

    private WellcomeEditorialCreationProcess createProcess(Path csvFile, List<Path> tifFiles, Prefs prefs, Process templateNew,
            Process templateUpdate) throws Exception {
        CSVUtil csv = new CSVUtil(csvFile);
        String referenceNumber = csv.getValue("Reference", 0);

        boolean existsOnS3 = checkIfExistsOnS3(referenceNumber);
        List<Path> newTifFiles = new ArrayList<>();
        if (existsOnS3 && referenceNumber.startsWith("EP")) {
            Map<String, String> oldFilesMap = getOldHashesFromS3(referenceNumber);
            List<String> checksums = new ArrayList<>();
            // first, check the files that are kept. 
            int max = computeHighestNumAndSha1Sums(tifFiles, oldFilesMap, checksums);
            generateNewNamesEPs3(tifFiles, referenceNumber, newTifFiles, oldFilesMap, checksums, max);
        } else {
            int count = 1;
            for (Path tifFile : tifFiles) {
                String fileName = tifFile.getFileName().toString();
                String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
                String newFileName = fileName;
                //only rename the EP shoot names
                if (referenceNumber.startsWith("EP")) {
                    newFileName = referenceNumber.replaceAll(" |\t", "_") + String.format("_%03d", count) + ext;
                }
                newTifFiles.add(tifFile.getParent().resolve(newFileName));
                count++;
            }
        }
        Fileformat ff = convertData(csv, newTifFiles, prefs);
        if (ff == null) {
            return null;
        }

        Process process = null;

        boolean existsInGoobi = ProcessManager.countProcessTitle(referenceNumber.replaceAll(" |\t", "_")) > 0;

        if (existsOnS3) {
            process = cloneTemplate(templateUpdate);
        } else if (existsInGoobi) {
            // does exist in Goobi, but not on S3 => wait (return error)
            WellcomeEditorialCreationProcess wecp = new WellcomeEditorialCreationProcess();
            return wecp;
        } else {
            process = cloneTemplate(templateNew);
        }

        // set title
        process.setTitel(referenceNumber.replaceAll(" |\t", "_"));

        NeuenProzessAnlegen(process, templateNew, ff, prefs);

        saveProperty(process, "b-number", referenceNumber);
        saveProperty(process, "CollectionName1", "Editorial Photography");
        saveProperty(process, "CollectionName2", referenceNumber);
        saveProperty(process, "securityTag", "open");
        saveProperty(process, "schemaName", "Millennium");
        saveProperty(process, "archiveStatus", referenceNumber.startsWith("CP") ? "archived" : "contemporary");

        saveProperty(process, "Keywords", csv.getValue("People") + ", " + csv.getValue("Keywords"));
        String creators = "";
        String staff = csv.getValue("Staff Photog");
        String freelance = csv.getValue("Freelance Photog");
        if (staff != null && !staff.isEmpty()) {
            creators = staff;
            if (freelance != null && !freelance.isEmpty()) {
                creators += "/" + freelance;
            }
        } else if (!freelance.isEmpty()) {
            creators = freelance;
        }
        saveProperty(process, "Creators", creators);

        //copy the files
        Path processDir = Paths.get(process.getProcessDataDirectory());
        Path importDir = processDir.resolve("import");
        Files.createDirectories(importDir);
        log.trace(String.format("Copying %s to %s (size: %d)",
                csvFile.toAbsolutePath().toString(),
                importDir.resolve(csvFile.getFileName()).toString(),
                Files.size(csvFile)));
        Files.copy(csvFile, importDir.resolve(csvFile.getFileName()));

        Path imagesDir = Paths.get(process.getImagesOrigDirectory(false));
        int count = 0;
        for (Path tifFile : tifFiles) {
            String newFileName = newTifFiles.get(count).getFileName().toString();
            log.trace(String.format("Copying %s to %s (size: %d)",
                    tifFile.toAbsolutePath().toString(),
                    imagesDir.resolve(newFileName).toString(),
                    Files.size(tifFile)));
            Files.copy(tifFile, imagesDir.resolve(newFileName));
            count++;
        }

        WellcomeEditorialCreationProcess wcp = new WellcomeEditorialCreationProcess();
        wcp.setProcessId(process.getId());
        wcp.setProcessName(process.getTitel());

        //start work for process
        List<Step> steps = StepManager.getStepsForProcess(process.getId());
        for (Step s : steps) {
            if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                myThread.start();
            }
        }
        return wcp;
    }

    public static int computeHighestNumAndSha1Sums(List<Path> tifFiles, Map<String, String> oldFilesMap, List<String> checksums) throws NoSuchAlgorithmException,
            IOException {
        int max = 0;
        for (Path tifFile : tifFiles) {
            String sha1 = generateSha1(tifFile.toAbsolutePath().toString());
            checksums.add(sha1);
            String oldName = oldFilesMap.get(sha1);
            if (oldName != null) {
                int lastDot = oldName.lastIndexOf('.');
                int number = Integer.parseInt(oldName.substring(lastDot - 3, lastDot));
                if (number > max) {
                    max = number;
                }
            }
        }
        return max;
    }

    public static void generateNewNamesEPs3(List<Path> tifFiles, String referenceNumber, List<Path> newTifFiles, Map<String, String> oldFilesMap,
            List<String> checksums, int max) {
        int count = 0;
        int pagination = max + 1;
        for (Path tifFile : tifFiles) {
            String sha1 = checksums.get(count);
            String oldName = oldFilesMap.get(sha1);
            if (oldName != null) {
                newTifFiles.add(tifFile.getParent().resolve(oldName));
            } else {
                String fileName = tifFile.getFileName().toString();
                String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
                String newFileName = String.format("%s_%03d%s", referenceNumber.replaceAll(" |\t", "_"), pagination, ext);
                newTifFiles.add(tifFile.getParent().resolve(newFileName));
            }
            count++;
        }
    }

    private Map<String, String> getOldHashesFromS3(final String _reference) throws JDOMException, IOException {
        String reference = _reference.replaceAll(" |\t", "_");
        int refLen = reference.length();
        String bucket = "wellcomecollection-editorial-photography";
        String keyPrefix = reference.substring(refLen - 2, refLen) + "/" + reference + "/";
        String key = keyPrefix + reference + ".xml";
        AmazonS3 s3client = AmazonS3ClientBuilder.defaultClient();
        S3Object s3o = s3client.getObject(bucket, key);
        SAXBuilder sb = new SAXBuilder();
        Document metsDoc = sb.build(s3o.getObjectContent());

        Map<String, String> oldHashesMap = new HashMap<>();
        List<Element> masterFiles = masterXpath.evaluate(metsDoc);
        for (int i = 0; i < masterFiles.size(); i++) {
            Element masterFile = masterFiles.get(i);

            String checksum = masterFile.getAttributeValue("CHECKSUM");
            String masterHref = masterFile.getChild("FLocat", metsNs).getAttributeValue("href", xlinkNs);
            String oldName = keyPrefix + masterHref.replace("file://", "");

            oldHashesMap.put(checksum, oldName);
        }
        return oldHashesMap;
    }

    private boolean checkIfExistsOnS3(final String _reference) {
        String reference = _reference.replaceAll(" |\t", "_");
        int refLen = reference.length();
        String bucket = "wellcomecollection-editorial-photography";
        String keyPrefix = reference.substring(refLen - 2, refLen) + "/" + reference + "/";
        String key = keyPrefix + reference + ".xml";
        AmazonS3 s3client = AmazonS3ClientBuilder.defaultClient();
        return s3client.doesObjectExist(bucket, key);
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
            md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
            md.setValue(csv.getValue("Reference").replaceAll(" |\t", "_"));
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

            String name = csv.getValue("Staff Photog");
            if (!name.isEmpty()) {
                Person p = new Person(prefs.getMetadataTypeByName("Photographer"));
                int lastSpace = name.lastIndexOf(' ');
                String firstName = name.substring(0, lastSpace);
                String lastName = name.substring(lastSpace + 1, name.length());
                p.setFirstname(firstName);
                p.setLastname(lastName);
                dsRoot.addPerson(p);
            }

            name = csv.getValue("Freelance Photog");
            if (!name.isEmpty()) {
                Person p = new Person(prefs.getMetadataTypeByName("Creator"));
                int lastSpace = name.lastIndexOf(' ');
                String firstName = name.substring(0, lastSpace);
                String lastName = name.substring(lastSpace + 1, name.length());
                p.setFirstname(firstName);
                p.setLastname(lastName);
                dsRoot.addPerson(p);
            }

            dd.setLogicalDocStruct(dsRoot);

            DocStruct dsBoundBook = dd.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            //TODO add files to dsBoundBook (correctly)
            int pageNo = 0;
            for (Path tifPath : tifFiles) {
                DocStruct page = dd.createDocStruct(prefs.getDocStrctTypeByName("page"));
                try {
                    // physical page no
                    dsBoundBook.addChild(page);
                    MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
                    Metadata mdTemp = new Metadata(mdt);
                    mdTemp.setValue(String.valueOf(pageNo));
                    page.addMetadata(mdTemp);

                    // logical page no
                    mdt = prefs.getMetadataTypeByName("logicalPageNumber");
                    mdTemp = new Metadata(mdt);

                    mdTemp.setValue("uncounted");

                    page.addMetadata(mdTemp);
                    ContentFile cf = new ContentFile();

                    cf.setLocation("file://" + tifPath.toAbsolutePath().toString());

                    page.addContentFile(cf);

                } catch (TypeNotAllowedAsChildException e) {
                    log.error(e);
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }
                pageNo++;
            }

            dd.setPhysicalDocStruct(dsBoundBook);

            // Collect MODS metadata

            // Add dummy volume to anchors ??
            //            generateDefaultValues(prefs, collectionName, dsRoot, dsBoundBook);

        } catch (PreferencesException | TypeNotAllowedForParentException | MetadataTypeNotAllowedException e) {
            log.error(e);
        }
        return ff;
    }

    private WellcomeEditorialCreationResponse createErrorResponse(String errorText) {
        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
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

    private static String generateSha1(String fileName) throws NoSuchAlgorithmException, IOException {
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
        FileInputStream fis = new FileInputStream(fileName);
        try {
            byte[] data = new byte[1024];

            int n = 0;
            while ((n = fis.read(data)) != -1) {
                messageDigest.update(data, 0, n);
            }
            byte[] messageDigestBytes = messageDigest.digest();

            BigInteger bigInt = new BigInteger(1, messageDigestBytes);

            return String.format("%40X", bigInt).toLowerCase();
        } finally {
            fis.close();
        }
    }
}
