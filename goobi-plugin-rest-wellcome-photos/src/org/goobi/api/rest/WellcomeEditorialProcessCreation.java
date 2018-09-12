package org.goobi.api.rest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.ws.rs.Consumes;
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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.PropertyType;
import de.sub.goobi.helper.enums.StepEditType;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
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
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@javax.ws.rs.Path("/wellcome")
@Log4j
public class WellcomeEditorialProcessCreation {

	private String currentIdentifier;
	private String currentWellcomeIdentifier;

	@javax.ws.rs.Path("/createeditorials")
	@POST
	@Produces("text/xml")
	@Consumes("application/json")
	public Response createNewProcess(Creator creator) {
		
		String workingStorage = System.getenv("WORKING_STORAGE");
		Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());
		try {
			StorageProvider.getInstance().createDirectories(workDir);
		} catch (IOException e1) {
			log.error("Unable to create temporary directory", e1);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(createErrorResponse("Unable to create temporary directory")).build();
		}
		// download and unpack zip
		try {
			Path zipFile = downloadZip(creator.getBucket(), creator.getKey(), workDir);//
			unzip(zipFile, workDir);
			StorageProvider.getInstance().deleteFile(zipFile);
		} catch (IOException e1) {
			log.error("Unable to move zip-file contents to working directory", e1);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(createErrorResponse("Unable to access temporary directory")).build();
		}

		Process templateUpdate = ProcessManager.getProcessById(creator.getUpdatetemplateid());
		Process templateNew = ProcessManager.getProcessById(creator.getTemplateid());
		if (templateNew == null) {
			Response resp = Response.status(Response.Status.BAD_REQUEST)
					.entity(createErrorResponse("Cannot find process template with id " + creator.getTemplateid()))
					.build();
			return resp;
		}
		Prefs prefs = templateNew.getRegelsatz().getPreferences();
		List<WellcomeEditorialCreationProcess> processes = new ArrayList<>();
		log.debug("working with folder " + workDir.getFileName());
		List<Path> tifFiles = new ArrayList<>();
		Path csvFile = null;
		try (DirectoryStream<Path> folderFiles = Files.newDirectoryStream(workDir)) {
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
		} catch (IOException e1) {
			log.error(e1);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(createErrorResponse("Error reading directory: " + workDir)).build();
		}
		Collections.sort(tifFiles);
		try {
			WellcomeEditorialCreationProcess wcp = createProcess(csvFile, tifFiles, prefs, templateNew, templateUpdate);
			if (wcp == null) {
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(createErrorResponse("Cannot import csv file: " + csvFile)).build();
			}
			wcp.setSourceFolder(workDir.getFileName().toString());
			processes.add(wcp);
			if (!(wcp.getProcessId() == 0)) {
				// process created. Now delete this folder.
				FileUtils.deleteQuietly(workDir.toFile());
			}
		} catch (FileNotFoundException e) {
			log.error("Cannot import csv file: " + csvFile + "\n", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(createErrorResponse("Cannot import csv file: " + csvFile)).build();
		} catch (PreferencesException | WriteException | ReadException | IOException | InterruptedException
				| SwapException | DAOException e) {
			log.error("Unable to create Goobi Process\n", e);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(createErrorResponse("Unable to create Goobi Process")).build();
		}

		WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
		resp.setProcesses(processes);
		resp.setResult("success");
		return Response.status(Response.Status.OK).entity(resp).build();
	}

	private void unzip(final Path zipFile, final Path output) throws IOException {
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
		}
	}

	/**
	 * parse passed uri and download file to passed folder, returns path of
	 * downloaded file for further use
	 */
	private Path downloadZip(String bucket, String s3Key, Path targetDir) throws IOException {
		AmazonS3 s3 = null;// AmazonS3ClientBuilder.defaultClient();
		ConfigurationHelper conf = ConfigurationHelper.getInstance();
		if (conf.useCustomS3()) {
			AWSCredentials credentials = new BasicAWSCredentials(conf.getS3AccessKeyID(), conf.getS3SecretAccessKey());
			ClientConfiguration clientConfiguration = new ClientConfiguration();
			clientConfiguration.setSignerOverride("AWSS3V4SignerType");

			s3 = AmazonS3ClientBuilder.standard()
					.withEndpointConfiguration(
							new AwsClientBuilder.EndpointConfiguration(conf.getS3Endpoint(), Regions.US_EAST_1.name()))
					.withPathStyleAccessEnabled(true).withClientConfiguration(clientConfiguration)
					.withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
		} else {
			s3 = AmazonS3ClientBuilder.defaultClient();
		}

		S3Object obj = s3.getObject(bucket, s3Key);
		int index = s3Key.lastIndexOf("/");
		Path targetPath;
		if (index != -1) {
			targetPath = targetDir.resolve(s3Key.substring(index, s3Key.length() - 1));
		} else {
			targetPath = targetDir.resolve(s3Key);
		}

		try (InputStream in = obj.getObjectContent()) {
			Files.copy(in, targetPath);
		}
		return targetPath;
	}

	private WellcomeEditorialCreationProcess createProcess(Path csvFile, List<Path> tifFiles, Prefs prefs,
			Process templateNew, Process templateUpdate) throws FileNotFoundException, IOException,
			InterruptedException, SwapException, DAOException, PreferencesException, WriteException, ReadException {
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
				"prozesse.titel='" + referenceNumber.replaceAll(" |\t", "_") + "'");
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
		StorageProvider.getInstance().copyFile(csvFile, importDir.resolve(csvFile.getFileName()));

		Path imagesDir = Paths.get(process.getImagesOrigDirectory(false));
		count = 0;
		for (Path tifFile : tifFiles) {
			String newFileName = newTifFiles.get(count).getFileName().toString();
			log.trace(String.format("Copying %s to %s (size: %d)", tifFile.toAbsolutePath().toString(),
					imagesDir.resolve(newFileName).toString(), Files.size(tifFile)));
			StorageProvider.getInstance().copyFile(tifFile, imagesDir.resolve(newFileName));
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
		if (ConfigurationHelper.getInstance().useCustomS3()) {
			return false;
		}
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

	public void NeuenProzessAnlegen(Process process, Process template, Fileformat ff, Prefs prefs) throws DAOException,
			PreferencesException, IOException, InterruptedException, SwapException, WriteException, ReadException {

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
