package org.goobi.api.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.log4j.Level;
import org.goobi.api.rest.response.WellcomeEditorialCreationProcess;
import org.goobi.api.rest.response.WellcomeEditorialCreationResponse;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.managedbeans.LoginBean;
import org.goobi.production.flow.jobs.HistoryAnalyserJob;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

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
	private static AmazonS3 s3 = null;
	private Pattern p = Pattern.compile("/(:?efs|opt)/digiverso/goobi/metadata/?");
	private String bucketIn;
	private String bucketOut;

	@javax.ws.rs.Path("/createeditorials")
	@POST
	@Produces("text/xml")
	public Response createNewProcess(@HeaderParam("templateid") int templateNewId,
			@HeaderParam("updatetemplateid") int templateUpdateId, @HeaderParam("hotfolder") String hotFolder,
			String s3Uri) {

		String workingStorage = System.getenv("WORKING_STORAGE");
		Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());
		try {
			Files.createDirectories(workDir);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			log.error(e1);
		}
		try {
			downloadPrefixToDir(s3Uri, workDir);// TODO download zip and unzip in dir
			Path zipFile = null;// return from download?
			unzip(zipFile, workDir);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			log.error(e1);
		}
		/*
		 * Path hotFolderPath = Paths.get(hotFolder); if (!Files.exists(hotFolderPath)
		 * || !Files.isDirectory(hotFolderPath)) { Response resp =
		 * Response.status(Response.Status.BAD_REQUEST)
		 * .entity(createErrorResponse("Hotfolder does not exist or is no directory " +
		 * hotFolder)).build(); return resp; }
		 */

		Process templateUpdate = ProcessManager.getProcessById(templateUpdateId);
		Process templateNew = ProcessManager.getProcessById(templateNewId);
		if (templateNew == null) {
			Response resp = Response.status(Response.Status.BAD_REQUEST)
					.entity(createErrorResponse("Cannot find process template with id " + templateNewId)).build();
			return resp;
		}

		Prefs prefs = templateNew.getRegelsatz().getPreferences();
		List<WellcomeEditorialCreationProcess> processes = new ArrayList<>();

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(workDir)) {
			for (Path dir : ds) {
				log.debug("working with folder " + dir.getFileName());
				/*
				 * if (!checkIfCopyingDone(dir)) { continue; }
				 */
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
						if ((fileNameLower.endsWith(".tif") || fileNameLower.endsWith(".tiff")
								|| fileNameLower.endsWith(".mp4")) && !fileNameLower.startsWith(".")) {
							tifFiles.add(file);
						}
					}
				}
				Collections.sort(tifFiles);
				try {
					WellcomeEditorialCreationProcess wcp = createProcess(csvFile, tifFiles, prefs, templateNew,
							templateUpdate);
					if (wcp == null) {
						return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
								.entity(createErrorResponse("Cannot import csv file: " + csvFile)).build();
					}
					wcp.setSourceFolder(dir.getFileName().toString());
					processes.add(wcp);
					if (wcp.getProcessId() == 0) {
						// no process created. Delete lockfile and
						Files.delete(lockFile);
					} else {
						// process created. Now delete this folder.
						FileUtils.deleteQuietly(dir.toFile());
					}
				} catch (Exception e) {
					// TODO: this should be collected and be returned as one at the end
					log.error(e);
					return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
							.entity(createErrorResponse("Cannot import csv file: " + csvFile)).build();
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			log.error(e);
		}
		try {
			removeDir(workDir);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			log.error(e);
		}
		WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
		resp.setProcesses(processes);
		resp.setResult("success");
		return Response.status(Response.Status.OK).entity(resp).build();
	}

	private void uploadFile(String bucketName, String fileKey, Path file) {
		try {
			s3.putObject(bucketName, fileKey, file.toString());
		} finally {
		}
	}

	private void removeDir(Path rootFolder) throws IOException {
		if (Files.isDirectory(rootFolder)) {
			DirectoryStream<Path> directoryStream = Files.newDirectoryStream(rootFolder);
			for (Path path : directoryStream) {
				removeDir(path);
			}
			Files.delete(rootFolder);
		} else {
			Files.delete(rootFolder);
		}
	}

	private void unzip(final Path zipFile, final Path output) {
		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFile))) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				final Path toPath = output.resolve(entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectory(toPath);
				} else {
					Files.copy(zipInputStream, toPath);
				}
			}
		} catch (IOException e) {
			log.error(e);
		}
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

	/**
	 * downloads files from passed prefix to passed folder
	 * 
	 * requires "s3_DATA_BUCKET" to be set correctly reads s3 settings from config
	 * file
	 * 
	 * @param prefix where files are
	 * @param dir    path to directory where files will be placed
	 */
	private void downloadPrefixToDir(String prefix, Path dir) throws IOException {
		XMLConfiguration pc = null;
		String bucket = null;
		try {
			bucket = System.getenv("S3_DATA_BUCKET");
		} catch (IllegalArgumentException e) {
			log.error("S3 Bucket enviroment variable not set");
		}
		try {
			pc = new XMLConfiguration("s3Config.xml");
		} catch (org.apache.commons.configuration.ConfigurationException e) {
			log.error("s3 Config-file not found");
		}

		if (pc.getBoolean("useCustomS3", false)) {
			AWSCredentials credentials = new BasicAWSCredentials(pc.getString("S3AccessKeyID"),
					pc.getString("S3SecretAccessKey"));
			ClientConfiguration clientConfiguration = new ClientConfiguration();
			clientConfiguration.setSignerOverride("AWSS3V4SignerType");

			s3 = AmazonS3ClientBuilder.standard()
					.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(pc.getString("S3_ENDPOINT"),
							Regions.US_EAST_1.name()))
					.withPathStyleAccessEnabled(true).withClientConfiguration(clientConfiguration)
					.withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
		}
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		String cleanedPrefix = p.matcher(prefix).replaceAll("");
		if (!cleanedPrefix.endsWith("/")) {
			cleanedPrefix = cleanedPrefix + "/";
		}
		ObjectListing listing = s3.listObjects(bucket, cleanedPrefix);
		for (S3ObjectSummary os : listing.getObjectSummaries()) {
			downloadS3ObjectToFolder(cleanedPrefix, dir, os);
		}
		while (listing.isTruncated()) {
			listing = s3.listNextBatchOfObjects(listing);
			for (S3ObjectSummary os : listing.getObjectSummaries()) {
				downloadS3ObjectToFolder(cleanedPrefix, dir, os);
			}
		}

	}

	/**
	 * downloads file at passed s3 prefix to passed folder
	 * 
	 * uses object variable s3
	 * 
	 * @param sourcePrefix prefix of file
	 * @param target       path to folder where downloaded file will be placed
	 */
	private void downloadS3ObjectToFolder(String sourcePrefix, Path target, S3ObjectSummary os) throws IOException {
		String key = os.getKey();
		Path targetPath = target.resolve(key.replace(sourcePrefix, ""));
		S3Object obj = s3.getObject(os.getBucketName(), key);
		try (InputStream in = obj.getObjectContent()) {
			Files.copy(in, targetPath);
		}
	}

	private WellcomeEditorialCreationProcess createProcess(Path csvFile, List<Path> tifFiles, Prefs prefs,
			Process templateNew, Process templateUpdate) throws Exception {
		CSVUtil csv = new CSVUtil(csvFile);
		String referenceNumber = csv.getValue("Reference", 0);
		List<Path> newTifFiles = new ArrayList<>();
		int count = 1;
		for (Path tifFile : tifFiles) {
			String fileName = tifFile.getFileName().toString();
			String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
			String newFileName = fileName;
			// only rename the EP shoot names
			if (referenceNumber.startsWith("EP")) {
				newFileName = referenceNumber.replaceAll(" |\t", "_") + String.format("_%03d", count) + ext;
			}
			newTifFiles.add(tifFile.getParent().resolve(newFileName));
			count++;
		}
		Fileformat ff = convertData(csv, newTifFiles, prefs);
		if (ff == null) {
			return null;
		}

		Process process = null;

		boolean existsInGoobiNotDone = false;
		List<Process> processes = ProcessManager.getProcesses("",
				"prozesse.titel=\"" + referenceNumber.replaceAll(" |\t", "_") + "\"");
		log.debug("found " + processes.size() + " processes with title " + referenceNumber.replaceAll(" |\t", "_"));
		for (Process p : processes) {
			if (!p.getSortHelperStatus().equals("100000000")) {
				existsInGoobiNotDone = true;
				break;
			}
		}
		boolean existsOnS3 = checkIfExistsOnS3(referenceNumber);

		if (existsInGoobiNotDone) {
			// does exist in Goobi, but is not done => wait (return error)
			WellcomeEditorialCreationProcess wecp = new WellcomeEditorialCreationProcess();
			return wecp;
		} else if (existsOnS3) {
			// is already on s3, but everything in Goobi went through => update
			process = cloneTemplate(templateUpdate);
		} else {
			// not in Goobi and not on s3 => new shoot
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

		// copy the files
		Path processDir = Paths.get(process.getProcessDataDirectory());
		Path importDir = processDir.resolve("import");
		Files.createDirectories(importDir);
		log.trace(String.format("Copying %s to %s (size: %d)", csvFile.toAbsolutePath().toString(),
				importDir.resolve(csvFile.getFileName()).toString(), Files.size(csvFile)));
		Files.copy(csvFile, importDir.resolve(csvFile.getFileName()));

		Path imagesDir = Paths.get(process.getImagesOrigDirectory(false));
		count = 0;
		for (Path tifFile : tifFiles) {
			String newFileName = newTifFiles.get(count).getFileName().toString();
			log.trace(String.format("Copying %s to %s (size: %d)", tifFile.toAbsolutePath().toString(),
					imagesDir.resolve(newFileName).toString(), Files.size(tifFile)));
			Files.copy(tifFile, imagesDir.resolve(newFileName));
			count++;
		}

		WellcomeEditorialCreationProcess wcp = new WellcomeEditorialCreationProcess();
		wcp.setProcessId(process.getId());
		wcp.setProcessName(process.getTitel());

		// start work for process
		List<Step> steps = StepManager.getStepsForProcess(process.getId());
		for (Step s : steps) {
			if (s.getBearbeitungsstatusEnum().equals(StepStatus.OPEN) && s.isTypAutomatisch()) {
				ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
				myThread.start();
			}
		}
		return wcp;
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
			// TODO add files to dsBoundBook (correctly)
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
			// generateDefaultValues(prefs, collectionName, dsRoot, dsBoundBook);

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
		 * -------------------------------- Imagepfad hinzufügen (evtl. vorhandene
		 * zunächst löschen) --------------------------------
		 */
		try {
			MetadataType mdt = prefs.getMetadataTypeByName("pathimagefiles");
			List<? extends Metadata> alleImagepfade = ff.getDigitalDocument().getPhysicalDocStruct()
					.getAllMetadataByType(mdt);
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
}
