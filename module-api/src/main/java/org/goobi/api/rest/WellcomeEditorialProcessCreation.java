package org.goobi.api.rest;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import javax.jms.JMSException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.goobi.api.mq.QueueType;
import org.goobi.api.mq.TaskTicket;
import org.goobi.api.mq.TicketGenerator;
import org.goobi.api.rest.response.WellcomeEditorialCreationProcess;
import org.goobi.api.rest.response.WellcomeEditorialCreationResponse;
import org.goobi.beans.Process;

import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j;

@javax.ws.rs.Path("/wellcome")
@Log4j
public class WellcomeEditorialProcessCreation {

    @javax.ws.rs.Path("/uploadaudio")
    @POST
    @Produces("text/xml")
    @Consumes("application/json")
    public Response uploadAudioDataToExistingProcess(Creator creator) {

        String processName = creator.getKey();

        int index = processName.lastIndexOf("/");
        if (index != -1) {

            String prefixWithoutFilename = processName.substring(0, index);
            index = prefixWithoutFilename.lastIndexOf("/");
            processName = prefixWithoutFilename.substring(index + 1, prefixWithoutFilename.length());
        }

        // exact search
        Process process = ProcessManager.getProcessByExactTitle(processName);
        if (process == null) {
            // like search
            process = ProcessManager.getProcessByTitle(processName);
        }
        if (process == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Cannot find process with title " + processName)).build();
        }
        String workingStorage = System.getenv("WORKING_STORAGE");
        Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());
        try {
            TaskTicket ticket = TicketGenerator.generateSimpleTicket("importAudioData");
            ticket.setProcessId(process.getId());
            ticket.setProcessName(process.getTitel());
            ticket.getProperties().put("bucket", creator.getBucket());
            ticket.getProperties().put("s3Key", creator.getKey());
            ticket.getProperties().put("targetDir", workDir.toString());
            ticket.getProperties().put("destination", process.getImagesTifDirectory(false));
            ticket.getProperties().put("deleteFiles", "true");
            TicketGenerator.submitInternalTicket(ticket, QueueType.SLOW_QUEUE, "importAudioData", 0);
        } catch (IOException | SwapException | JMSException e2) {
            log.error(e2);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(createErrorResponse("Cannot add ticket to import data for " + processName))
                    .build();
        }

        WellcomeEditorialCreationProcess wcp = new WellcomeEditorialCreationProcess();
        wcp.setProcessId(process.getId());
        wcp.setProcessName(process.getTitel());
        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
        resp.setProcess(wcp);
        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();
    }

    @javax.ws.rs.Path("/uploadvideo")
    @POST
    @Produces("text/xml")
    @Consumes("application/json")
    public Response uploadVideoDataToExistingProcess(Creator creator) {

        String processName = creator.getKey();

        int index = processName.lastIndexOf("/");
        if (index != -1) {

            String prefixWithoutFilename = processName.substring(0, index);
            index = prefixWithoutFilename.lastIndexOf("/");
            processName = prefixWithoutFilename.substring(index + 1, prefixWithoutFilename.length());
        }

        // exact search
        Process process = ProcessManager.getProcessByExactTitle(processName);
        if (process == null) {
            // like search
            process = ProcessManager.getProcessByTitle(processName);
        }
        if (process == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Cannot find process with title " + processName)).build();
        }
        String workingStorage = System.getenv("WORKING_STORAGE");
        Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());
        try {
            TaskTicket ticket = TicketGenerator.generateSimpleTicket("importVideoData");
            ticket.setProcessId(process.getId());
            ticket.setProcessName(process.getTitel());
            ticket.getProperties().put("bucket", creator.getBucket());
            ticket.getProperties().put("s3Key", creator.getKey());
            ticket.getProperties().put("targetDir", workDir.toString());
            ticket.getProperties().put("destination", process.getImagesTifDirectory(false));
            ticket.getProperties().put("deleteFiles", "true");
            TicketGenerator.submitInternalTicket(ticket, QueueType.SLOW_QUEUE, "importVideoData", 0);
        } catch (IOException | SwapException | JMSException e2) {
            log.error(e2);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(createErrorResponse("Cannot add ticket to import data for " + processName))
                    .build();
        }

        WellcomeEditorialCreationProcess wcp = new WellcomeEditorialCreationProcess();
        wcp.setProcessId(process.getId());
        wcp.setProcessName(process.getTitel());
        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
        resp.setProcess(wcp);
        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();
    }

    @javax.ws.rs.Path("/uploadzip")
    @POST
    @Produces("text/xml")
    @Consumes("application/json")
    public Response uploadZipfileDataToExistingProcess(Creator creator) {

        String processName = creator.getKey();
        int index = processName.lastIndexOf("/");
        if (index != -1) {
            processName = processName.substring(index + 1, processName.length());
        }
        processName = processName.replace(".zip", "");
        // exact search
        Process process = ProcessManager.getProcessByExactTitle(processName);
        if (process == null) {
            // like search
            process = ProcessManager.getProcessByTitle(processName);
        }
        if (process == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(createErrorResponse("Cannot find process with title " + processName)).build();
        }
        String workingStorage = System.getenv("WORKING_STORAGE");
        Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());

        try {
            TaskTicket ticket = TicketGenerator.generateSimpleTicket("downloads3");
            ticket.setProcessId(process.getId());
            ticket.setProcessName(process.getTitel());
            ticket.getProperties().put("bucket", creator.getBucket());
            ticket.getProperties().put("s3Key", creator.getKey());
            ticket.getProperties().put("targetDir", workDir.toString());
            ticket.getProperties().put("destination", process.getImagesOrigDirectory(false)); // TODO remove this line after next update
            ticket.getProperties().put("tifFolder", process.getImagesOrigDirectory(false));
            ticket.getProperties().put("jp2Folder", process.getImagesTifDirectory(false));
            ticket.getProperties().put("deleteFiles", "true");
            TicketGenerator.submitInternalTicket(ticket, QueueType.SLOW_QUEUE, "downloads3", 0);
        } catch (IOException | SwapException | DAOException | JMSException e2) {
            log.error(e2);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(createErrorResponse("Cannot add ticket to import data for " + processName))
                    .build();
        }

        WellcomeEditorialCreationProcess wcp = new WellcomeEditorialCreationProcess();
        wcp.setProcessId(process.getId());
        wcp.setProcessName(process.getTitel());
        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
        resp.setProcess(wcp);
        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();

    }

    @javax.ws.rs.Path("/createeditorials")
    @POST
    @Produces("text/xml")
    @Consumes("application/json")
    public Response createNewProcess(Creator creator) throws JMSException {
        String workingStorage = System.getenv("WORKING_STORAGE");
        Path workDir = Paths.get(workingStorage, UUID.randomUUID().toString());

        // validation
        //        Process templateUpdate = ProcessManager.getProcessById(creator.getUpdatetemplateid());
        Process templateNew = ProcessManager.getProcessById(creator.getTemplateid());
        if (templateNew == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(createErrorResponse("Cannot find process template with id " + creator.getTemplateid()))
                    .build();
        }

        //        Process process = null;

        TaskTicket ticket = TicketGenerator.generateSimpleTicket("downloads3");

        ticket.getProperties().put("bucket", creator.getBucket());
        ticket.getProperties().put("s3Key", creator.getKey());
        ticket.getProperties().put("targetDir", workDir.toString());
        ticket.getProperties().put("deleteFiles", "false");

        ticket.getProperties().put("updateTemplateId", creator.getUpdatetemplateid() + "");
        ticket.getProperties().put("templateId", creator.getTemplateid() + "");

        TicketGenerator.submitInternalTicket(ticket, QueueType.SLOW_QUEUE, "downloads3Editorial", 0);

        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();

        resp.setResult("success");
        return Response.status(Response.Status.OK).entity(resp).build();
    }

    private WellcomeEditorialCreationResponse createErrorResponse(String errorText) {
        WellcomeEditorialCreationResponse resp = new WellcomeEditorialCreationResponse();
        resp.setResult("error");
        resp.setErrorText(errorText);
        return resp;
    }

}
