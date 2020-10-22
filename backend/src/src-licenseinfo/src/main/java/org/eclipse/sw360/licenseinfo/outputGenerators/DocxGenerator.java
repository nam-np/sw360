/*
 * Copyright Bosch Software Innovations GmbH, 2016.
 * With modifications by Siemens AG, 2018.
 * Part of the SW360 Portal Project.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.sw360.licenseinfo.outputGenerators;

import org.apache.commons.collections.CollectionUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFTable.XWPFBorderType;
import org.apache.thrift.TException;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.eclipse.sw360.datahandler.common.CommonUtils;
import org.eclipse.sw360.datahandler.common.SW360Utils;
import org.eclipse.sw360.datahandler.common.ThriftEnumUtils;
import org.eclipse.sw360.datahandler.common.SW360Utils.TodoInfo;
import org.eclipse.sw360.datahandler.thrift.SW360Exception;
import org.eclipse.sw360.datahandler.thrift.ThriftClients;
import org.eclipse.sw360.datahandler.thrift.components.Release;
import org.eclipse.sw360.datahandler.thrift.licenseinfo.*;
import org.eclipse.sw360.datahandler.thrift.licenses.License;
import org.eclipse.sw360.datahandler.thrift.licenses.LicenseService;
import org.eclipse.sw360.datahandler.thrift.projects.ObligationStatusInfo;
import org.eclipse.sw360.datahandler.thrift.projects.Project;
import org.eclipse.sw360.datahandler.thrift.users.User;
import org.eclipse.sw360.datahandler.thrift.users.UserService;
import org.eclipse.sw360.licenseinfo.util.LicenseNameWithTextUtils;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.eclipse.sw360.datahandler.common.CommonUtils.nullToEmptyString;
import static org.eclipse.sw360.datahandler.common.WrappedException.wrapTException;
import static org.eclipse.sw360.licenseinfo.outputGenerators.DocxUtils.*;

public class DocxGenerator extends OutputGenerator<byte[]> {
    private static final int TABLE_WIDTH = 8800;
    private static final String CAPTION_EXTID_TABLE_VALUE = "External Identifiers for this Product:";
    private static final String CAPTION_EXTID_TABLE = "$caption-extid-table";
    private static final String EXTERNAL_ID_TABLE = "$external-id-table";
    private static final String README_OSS_TEXT= "$readme-OSS-text";
    private static final String UNKNOWN_LICENSE_NAME = "Unknown license name";
    private static final String UNKNOWN_FILE_NAME = "Unknown file name";
    private static final String UNKNOWN_LICENSE = "Unknown";
    private static final String TODO_DEFAULT_TEXT = "Obligations not determined so far.";

    private static final String DOCX_TEMPLATE_FILE = "/templateFrontpageContent.docx";
    private static final String DOCX_TEMPLATE_REPORT_FILE = "/templateReport.docx";
    private static final String DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DOCX_OUTPUT_TYPE = "docx";
    private int noOfTablesCreated;

    private static final long ADDITIONAL_REQ_THRESHOLD = 3;

    public static final int OVERVIEW_TABLE_INDEX = 0;
    public static final int SPECIAL_OSS_RISKS_TABLE_INDEX = 1;
    public static final int DEV_DETAIL_TABLE_INDEX = 2;
    public static final int THIRD_PARTY_COMPONENT_OVERVIEW_TABLE_INDEX = 3;
    private static final int COMMON_RULES_TABLE_INDEX = 4;
    private static final int PROJECT_OBLIGATIONS_TABLE_INDEX = 5;
    public static final int ADDITIONAL_REQ_TABLE_INDEX = 6;
    public static int OBLIGATION_STATUS_TABLE_INDEX = 7;

    private static final String EXT_ID_TABLE_HEADER_COL1 = "Identifier Name";
    private static final String EXT_ID_TABLE_HEADER_COL2 = "Identifier Value";

    public DocxGenerator(OutputFormatVariant outputFormatVariant, String description) {
        super(DOCX_OUTPUT_TYPE, description, true, DOCX_MIME_TYPE, outputFormatVariant);
    }

    @Override
    public byte[] generateOutputFile(Collection<LicenseInfoParsingResult> projectLicenseInfoResults, Project project, Collection<ObligationParsingResult> obligationResults, User user, Map<String, String> externalIds, Map<String, ObligationStatusInfo> obligationsStatus) throws SW360Exception {
        String licenseInfoHeaderText = project.getLicenseInfoHeaderText();

        ByteArrayOutputStream docxOutputStream = new ByteArrayOutputStream();
        Optional<byte[]> docxTemplateFile;
        XWPFDocument xwpfDocument;
        try {
            switch (getOutputVariant()) {
                case DISCLOSURE:
                    docxTemplateFile = CommonUtils.loadResource(DocxGenerator.class, DOCX_TEMPLATE_FILE);
                    xwpfDocument = new XWPFDocument(new ByteArrayInputStream(docxTemplateFile.get()));
                    if (docxTemplateFile.isPresent()) {
                        fillDisclosureDocument(
                            xwpfDocument,
                            projectLicenseInfoResults,
                            project,
                            licenseInfoHeaderText,
                            false,
                            externalIds
                            );
                    } else {
                        throw new SW360Exception("Could not load the template for xwpf document: " + DOCX_TEMPLATE_FILE);
                    }
                    break;
                case REPORT:
                    docxTemplateFile = CommonUtils.loadResource(DocxGenerator.class, DOCX_TEMPLATE_REPORT_FILE);
                    xwpfDocument = new XWPFDocument(new ByteArrayInputStream(docxTemplateFile.get()));
                    if (docxTemplateFile.isPresent()) {
                        fillReportDocument(
                            xwpfDocument,
                            projectLicenseInfoResults,
                            project,
                            licenseInfoHeaderText,
                            true,
                            obligationResults,
                            user,
                            obligationsStatus
                        );
                    } else {
                        throw new SW360Exception("Could not load the template for xwpf document: " + DOCX_TEMPLATE_REPORT_FILE);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown generator variant type: " + getOutputVariant());
            }
            xwpfDocument.write(docxOutputStream);
            docxOutputStream.close();
        } catch (XmlException e) {
            throw new SW360Exception("Got XmlException while generating docx document: " + e.getMessage());
        } catch (IOException e) {
            throw new SW360Exception("Got IOException when generating docx document: " + e.getMessage());
        } catch (TException e) {
            throw new SW360Exception("Error reading sw360 licenses: " + e.getMessage());
        }
        return docxOutputStream.toByteArray();
    }

    private void fillDisclosureDocument(
        XWPFDocument document,
        Collection<LicenseInfoParsingResult> projectLicenseInfoResults,
        Project project,
        String licenseInfoHeaderText,
        boolean includeObligations, Map<String, String> externalIds) throws XmlException, TException {
            if (CommonUtils.isNotEmpty(projectLicenseInfoResults)) {
                projectLicenseInfoResults = projectLicenseInfoResults.stream().filter(Objects::nonNull)
                    .sorted(Comparator.comparing(li -> getComponentLongName(li), String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
            }
            Map<LicenseNameWithText, Integer> licenseToReferenceId  = populatelicenseToReferenceId(projectLicenseInfoResults, Maps.newHashMap());
            String projectName = project.getName();
            String projectVersion = project.getVersion();

            replaceText(document, "$license-info-header", licenseInfoHeaderText);
            replaceText(document, "$project-name", projectName);
            replaceText(document, "$project-version", projectVersion);
            fillExternalIds(document, externalIds);

            if (CommonUtils.isNullOrEmptyCollection(projectLicenseInfoResults)) {
                printErrorForNoRelease(document, projectLicenseInfoResults);
                return;
            }

            fillReleaseBulletList(document, projectLicenseInfoResults);
            fillReleaseDetailList(document, projectLicenseInfoResults, includeObligations, licenseToReferenceId);
            fillLicenseList(document, projectLicenseInfoResults, licenseToReferenceId);
    }

    private void printErrorForNoRelease(XWPFDocument document,
            Collection<LicenseInfoParsingResult> projectLicenseInfoResults) {
        XWPFRun errorRun = document.createParagraph().createRun();
        String errorText = "Either there is no releases based on your selection or the selected project does not contain any release";
        addFormattedText(errorRun, errorText, FONT_SIZE, false, ALERT_COLOR);
    }

    private Map<LicenseNameWithText, Integer> populatelicenseToReferenceId(Collection<LicenseInfoParsingResult> projectLicenseInfoResults, Map<LicenseNameWithText, Integer> licenseToReferenceId) {
        int referenceId = 1;
        List<LicenseNameWithText> licenseNamesWithTexts = getSortedLicenseNameWithTexts(projectLicenseInfoResults);
        for (LicenseNameWithText licenseNamesWithText : licenseNamesWithTexts) {
            licenseToReferenceId.put(licenseNamesWithText, referenceId++);
        }
        return licenseToReferenceId;
    }

    private void fillExternalIds(XWPFDocument document, Map<String, String> externalIdMap) {
        if(!externalIdMap.isEmpty()) {
            replaceText(document, CAPTION_EXTID_TABLE, CAPTION_EXTID_TABLE_VALUE);
            List<XWPFParagraph> list = document.getParagraphs().stream()
                    .filter(x -> x.getParagraphText().equalsIgnoreCase(EXTERNAL_ID_TABLE)).collect(Collectors.toList());
            if (!list.isEmpty()) {
                XmlCursor cursor = list.get(0).getCTP().newCursor();
                XWPFTable table = document.insertNewTbl(cursor);
                XWPFTableRow tableHeader = table.getRow(0);
                tableHeader.addNewTableCell();

                addFormattedText(tableHeader.getCell(0).addParagraph().createRun(),
                        EXT_ID_TABLE_HEADER_COL1, FONT_SIZE, true);
                addFormattedText(tableHeader.getCell(1).addParagraph().createRun(),
                        EXT_ID_TABLE_HEADER_COL2, FONT_SIZE, true);

                externalIdMap.entrySet().forEach(x -> {
                    XWPFTableRow row = table.createRow();
                    row.getCell(0).setText(x.getKey());
                    row.getCell(1).setText(x.getValue());
                });

                setTableRowSize(table);
                removeParagraph(document, EXTERNAL_ID_TABLE);
                addNewLines(document, 1);
            }
        }else {
            removeParagraph(document,EXTERNAL_ID_TABLE);
            removeParagraph(document, CAPTION_EXTID_TABLE);
        }
    }

    private void setTableRowSize(XWPFTable table) {
        for(int x = 0;x < table.getNumberOfRows(); x++){
            XWPFTableRow row = table.getRow(x);
            int numberOfCell = row.getTableCells().size();
            for(int y = 0; y < numberOfCell ; y++){
                XWPFTableCell cell = row.getCell(y);
                cell.getCTTc().addNewTcPr().addNewTcW().setW(BigInteger.valueOf(TABLE_WIDTH));
            }
          }
    }

    private void fillReportDocument(
        XWPFDocument document,
        Collection<LicenseInfoParsingResult> projectLicenseInfoResults,
        Project project,
        String licenseInfoHeaderText,
        boolean includeObligations,
        Collection<ObligationParsingResult> obligationResults,
        User user, Map<String, ObligationStatusInfo> obligationsStatus) throws XmlException, TException {

            String businessUnit = project.getBusinessUnit();
            String projectName = project.getName();
            String projectVersion = project.getVersion();
            String clearingSummaryText = project.getClearingSummary();
            String specialRisksOSSText = project.getSpecialRisksOSS();
            String generalRisks3rdPartyText = CommonUtils.isNotNullEmptyOrWhitespace(project.getGeneralRisks3rdParty()) ? project.getGeneralRisks3rdParty() : "No commercial 3rd party software is used";
            String specialRisks3rdPartyText = CommonUtils.isNotNullEmptyOrWhitespace(project.getSpecialRisks3rdParty()) ? project.getSpecialRisks3rdParty() : "No commercial 3rd party software is used";
            String deliveryChannelsText = project.getDeliveryChannels();
            String remarksAdditionalRequirementsText = project.getRemarksAdditionalRequirements();
            String projectDescription = project.getDescription();
            // extract licenses that appear at least ADDITIONAL_REQ_THRESHOLD times
            Set<String> mostLicenses = extractMostCommonLicenses(obligationResults, ADDITIONAL_REQ_THRESHOLD);

            fillOwnerGroup(document, project);
            fillAttendeesTable(document, project);

            replaceText(document, "$bunit", businessUnit);
            replaceText(document, "$license-info-header", licenseInfoHeaderText);
            replaceText(document, "$project-name", projectName);
            replaceText(document, "$project-version", projectVersion);
            replaceText(document, "$clearing-summary-text", clearingSummaryText);
            replaceText(document, "$special-risks-oss-addition-text", specialRisksOSSText);
            replaceText(document, "$general-risks-3rd-party-text", generalRisks3rdPartyText);
            replaceText(document, "$special-risks-3rd-party-text", specialRisks3rdPartyText);
            replaceText(document, "$delivery-channels-text", deliveryChannelsText);
            replaceText(document, "$remarks-additional-requirements-text", remarksAdditionalRequirementsText);
            replaceText(document, "$product-description", projectDescription);

            String readme_oss = "Is generated by the Clearing office and provided in sw360 as attachment of the Project. It is stored here:";
            replaceText(document, "$readme-OSS-text", readme_oss);
            replaceText(document, "$list_comma_sep_licenses_above_threshold", String.join(", ", mostLicenses));

            if (CommonUtils.isNullOrEmptyCollection(projectLicenseInfoResults)) {
                printErrorForNoRelease(document, projectLicenseInfoResults);
                return;
            } else {
                projectLicenseInfoResults = projectLicenseInfoResults.stream().filter(Objects::nonNull)
                    .sorted(Comparator.comparing(li -> getComponentShortName(li), String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
            }

            fillSpecialOSSRisksTable(document, project, obligationResults);
            fillDevelopmentDetailsTable(document, project, user, projectLicenseInfoResults);
            fillOverview3rdPartyComponentTable(document, projectLicenseInfoResults);
            
            fillOrganisationObligationsTable(document, project);
            fillProjectSpecificObligationsTable(document, project);
            fillComponentObligationsTable(document, obligationResults, mostLicenses, project);


            fillLinkedObligations(document, obligationsStatus);
            // because of the impossible API component subsections must be the last thing in the docx file
            // the rest of the sections must be generated after this
            writeComponentSubsections(document, projectLicenseInfoResults, obligationResults);
    }

    private void fillOwnerGroup(XWPFDocument document, Project project) throws XmlException, TException {
        String businessUnit = "";
        if(project.isSetBusinessUnit()) {
            businessUnit = project.getBusinessUnit();
        }
        replaceText(document, "$owner-group", businessUnit);
    }

    private void fillAttendeesTable(XWPFDocument document, Project project) throws XmlException, TException {
        XWPFTable table = document.getTables().get(OVERVIEW_TABLE_INDEX);

        int currentRow = 7;

        UserService.Iface userClient = new ThriftClients().makeUserClient();

        if(project.isSetProjectOwner() && !project.getProjectOwner().isEmpty()) {
            User owner = userClient.getByEmail(project.getProjectOwner());
            if(owner != null) {
                XWPFTableRow row = table.insertNewTableRow(currentRow++);
                row.addNewTableCell().setText(owner.getEmail());
                row.addNewTableCell().setText(owner.getDepartment().toString());
                row.addNewTableCell().setText("Owner");
            }
        }

        if(project.isSetRoles()) {
            for(Map.Entry<String,Set<String> > rolRelationship : project.getRoles().entrySet()) {

                String rol = rolRelationship.getKey();
                Set<String> emails = rolRelationship.getValue();
                for(String email : emails) {
                    if(email.isEmpty()) {
                        continue;
                    }

                    User user = null;
                    try {
                         user = userClient.getByEmail(email);
                    } catch (TException te) {
                        // a resulting null user object is handled below by replacing with email
                    }

                    XWPFTableRow row = table.insertNewTableRow(currentRow++);
                    String name = email;
                    if(user != null && user.isSetFullname()) {
                        name = user.getFullname();
                    }
                    String department = "N.A.";
                    if(user != null) {
                        department = user.getDepartment().toString();
                    }

                    row.addNewTableCell().setText(name);
                    row.addNewTableCell().setText(department);
                    row.addNewTableCell().setText(rol);
                }
            }
        }
    }

    private void fillSpecialOSSRisksTable(XWPFDocument document, Project project, Collection<ObligationParsingResult> obligationResults) throws XmlException, TException {
        XWPFTable table = document.getTables().get(SPECIAL_OSS_RISKS_TABLE_INDEX);
        final int[] currentRow = new int[]{0};

        obligationResults.stream()
                .filter(opr -> opr.getStatus() == ObligationInfoRequestStatus.SUCCESS)
                .flatMap(opr -> opr.getObligationsAtProject().stream())
                .distinct()
                .forEach(o ->
                        {
                            currentRow[0] = currentRow[0] + 1;
                            XWPFTableRow row = table.insertNewTableRow(currentRow[0]);
                            row.addNewTableCell().setText(o.getTopic());
                            row.addNewTableCell().setText(String.join(" ", o.getLicenseIDs()));
                            row.addNewTableCell().setText(o.getText());
                        }
                );
        setTableBorders(table);
    }

    private void fillOverview3rdPartyComponentTable(XWPFDocument document, Collection<LicenseInfoParsingResult> projectLicenseInfoResults) throws XmlException {
        XWPFTable table = document.getTables().get(THIRD_PARTY_COMPONENT_OVERVIEW_TABLE_INDEX);

        int currentRow = 1;
        for(LicenseInfoParsingResult result : projectLicenseInfoResults) {
            if(result.getStatus() != LicenseInfoRequestStatus.SUCCESS) {
                continue;
            }

            XWPFTableRow row = table.insertNewTableRow(currentRow++);
            LicenseInfo licenseInfo = result.getLicenseInfo();
            row.addNewTableCell().setText(result.getName());
            row.addNewTableCell().setText(result.getVersion());
            row.addNewTableCell().setText(licenseInfo.getSha1Hash());
            row.addNewTableCell().setText(licenseInfo.getComponentName());

            String globalLicense = "";
            for(LicenseNameWithText l : licenseInfo.getLicenseNamesWithTexts()) {
                if(l != null && "global".equals(l.getType())) {
                    globalLicense = l.getLicenseName();
                    break;
                }
            }

            row.addNewTableCell().setText(result.getComponentType());
            row.addNewTableCell().setText(globalLicense);
        }
        setTableBorders(table);
    }

    private static Optional<ObligationParsingResult> obligationsForRelease(Release release, Collection<ObligationParsingResult> obligationResults) {
        return obligationResults.stream().filter(opr -> opr.getRelease() == release).findFirst();
    }

    private void writeComponentSubsections(XWPFDocument document, Collection<LicenseInfoParsingResult> projectLicenseInfoResults, Collection<ObligationParsingResult> obligationResults) throws SW360Exception, XmlException {
        XmlCursor cursor = document.getTables().get(ADDITIONAL_REQ_TABLE_INDEX+getNoOfTablesCreated()).getCTTbl().newCursor();
        cursor.toEndToken();

        for (LicenseInfoParsingResult result : projectLicenseInfoResults) {
            while (cursor.currentTokenType() != XmlCursor.TokenType.START && cursor.hasNextToken()) {
                cursor.toNextToken();
            }

            if (cursor.currentTokenType() != XmlCursor.TokenType.START) {
                throw new SW360Exception("Corrupt template; unable find start token");
            }

            XWPFParagraph title = document.insertNewParagraph(cursor);
            title.setStyle(STYLE_HEADING_3);
            title.setNumID(new BigInteger("2"));
            XWPFRun titleRun = title.createRun();
            titleRun.setText(result.getVendor() + " " + result.getName());

            if (cursor.hasNextToken()) {
                cursor.toNextToken();
            } else {
                throw new SW360Exception("Corrupt template; unable to proceed to next token");
            }
            XWPFParagraph description = document.insertNewParagraph(cursor);
            XWPFRun descriptionRun = description.createRun();

            LicenseInfo licenseInfo = result.getLicenseInfo();
            String globalLicense = UNKNOWN_LICENSE;
            for (LicenseNameWithText l : licenseInfo.getLicenseNamesWithTexts()) {
                if ("global".equals(l.getType())) {
                    globalLicense = l.getLicenseName();
                    break;
                }
            }

            descriptionRun.setText("The component is licensed under " + globalLicense + ".");

            if (result.isSetRelease()) {
                Optional<ObligationParsingResult> obligationsResultOp = obligationsForRelease(result.getRelease(), obligationResults);

                if (!obligationsResultOp.isPresent()) {
                    continue;
                }

                ObligationParsingResult obligationsResult = obligationsResultOp.get();

                if (!obligationsResult.isSetObligationsAtProject()) {
                    continue;
                }

                int currentRow = 0;
                Collection<ObligationAtProject> obligationsAtProject = obligationsResult.getObligationsAtProject();
                XWPFTable table = document.insertNewTbl(cursor);
                for (ObligationAtProject o : obligationsAtProject) {
                    XWPFTableRow row = table.insertNewTableRow(currentRow++);
                    String licensesString = String.join(" ", o.getLicenseIDs());
                    row.addNewTableCell().setText(o.getTopic());
                    row.addNewTableCell().setText(licensesString);
                    row.addNewTableCell().setText(o.getText());
                }
                setTableBorders(table);
            }
        }
    }

    private void fillDevelopmentDetailsTable(XWPFDocument document, Project project, User user, Collection<LicenseInfoParsingResult> projectLicenseInfoResults) throws TException {
        XWPFTable table = document.getTables().get(DEV_DETAIL_TABLE_INDEX);

        int currentRow = 1;

        for(LicenseInfoParsingResult result : projectLicenseInfoResults) {
            if (result.getStatus() != LicenseInfoRequestStatus.SUCCESS) {
                // this error handling is for extra safety since projectLicenseInfoResults is provided by the caller
                // and we assume valid input so we silently ignoring it.
                continue;
            }

            Release r = result.getRelease();
            if (r == null) {
                continue;
            }

            XWPFTableRow row = table.insertNewTableRow(currentRow++);

            row.addNewTableCell().setText(r.getName());

            String operatingSystems = r.getOperatingSystemsSize() == 0 ? "N/A" : String.join(" ", r.getOperatingSystems());
            row.addNewTableCell().setText(operatingSystems);

            String langs = r.getLanguagesSize() == 0 ? "N/A" : String.join(" ", r.getLanguages());
            row.addNewTableCell().setText(langs);

            String platforms = r.getSoftwarePlatformsSize() == 0 ? "N/A" : String.join(" ", r.getSoftwarePlatforms());
            row.addNewTableCell().setText(platforms);
        }
        setTableBorders(table);
    }

    protected static Set<String> extractMostCommonLicenses(Collection<ObligationParsingResult> obligationResults, long threshold) {
        return obligationResults.stream()
                .filter(opr -> opr.getStatus() == ObligationInfoRequestStatus.SUCCESS)
                .flatMap(opr -> opr.getObligationsAtProject().stream())
                .flatMap(o -> o.getLicenseIDs().stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue().longValue() >= threshold)
                .map(entry -> entry.getKey())
                .map(license -> license.replace("\n", "").replace("\r", ""))
                .collect(Collectors.toSet());
    }

    private void fillComponentObligationsTable(XWPFDocument document, Collection<ObligationParsingResult> obligationResults, Set<String> mostLicenses, Project project) throws XmlException, SW360Exception {
        final int[] currentRow = new int[] { 0, 0 };
        final  Map<String, Map<String, String>> licenseIdToOblTopicText = new HashMap<String, Map<String, String>>();

        obligationResults.stream()
                .filter(opr -> opr.getStatus() == ObligationInfoRequestStatus.SUCCESS)
                .flatMap(opr -> opr.getObligationsAtProject().stream())
                .filter(o -> o.getLicenseIDs().stream()
                        .anyMatch(lid -> mostLicenses.parallelStream().anyMatch(mlid -> mlid.equals(lid.replace("\n", "").replace("\r", "")))))
                .forEach(o-> {
                    o.getLicenseIDs().stream().forEach(lid -> {
                        Map<String, String> oblTopicText = new HashMap<String, String>();
                        oblTopicText.put(o.getTopic(), o.getText());
                        licenseIdToOblTopicText.put(lid, oblTopicText);
                    });
                });

        setNoOfTablesCreated(licenseIdToOblTopicText.size());

        Set<Map.Entry<String, Map<String, String>>> entry = licenseIdToOblTopicText.entrySet();
        if (licenseIdToOblTopicText.size() > 0) {
            XWPFTable table = printFirstTable(document, licenseIdToOblTopicText, project);
            XmlCursor cursor = null;

            for (Map.Entry<String, Map<String, String>> entr : entry) {
                cursor = printLicenseNameHeader(document, table, entr.getKey());
                printSecondTableOnwards(document, cursor, entr, project);
            }

        }
    }

    private void printSecondTableOnwards(XWPFDocument document, XmlCursor cursor, Map.Entry<String, Map<String, String>> entr, Project project) {
        XWPFTable table = document.insertNewTbl(cursor);
        Map<String, String> tctxt = entr.getValue();
        int noOfRows = tctxt.size();
        final int[] currentRow = new int[]{noOfRows};

        XWPFTableRow row = table.getRow(0);
        row.getCell(0).setText("Obligation");
        row.addNewTableCell().setText("License");
        row.addNewTableCell().setText("License section reference and short description");
        row.addNewTableCell().setText("Fulfilled");
        row.addNewTableCell().setText("Comments");

        for (Map.Entry<String, String> entry : tctxt.entrySet()) {
            XWPFTableRow tableRow = table.createRow();
            tableRow.getCell(0).setText(entry.getKey());
            tableRow.getCell(1).setText(entr.getKey());
            tableRow.getCell(2).setText(entry.getValue());
            tableRow.getCell(3);
            tableRow.getCell(4);

        }

        SW360Utils.getComponentObligations(project).entrySet().stream().forEachOrdered(oblig -> {
            currentRow[0] = currentRow[0] + 1;
            XWPFTableRow crow = table.insertNewTableRow(currentRow[0]);
            crow.addNewTableCell().setText(oblig.getKey().getTitle());
            crow.addNewTableCell().setText(entr.getKey());
            crow.addNewTableCell().setText(oblig.getKey().getText());
            crow.addNewTableCell().setText(oblig.getValue().fulfilled ? "yes" : "no");
            crow.addNewTableCell().setText(oblig.getValue().getComments());
        });
        setTableBorders(table);
    }

    private XWPFTable printFirstTable(XWPFDocument document, Map<String, Map<String, String>> licenseIdToOblTopicText, Project project) {
        Set<Map.Entry<String, Map<String, String>>> entry = licenseIdToOblTopicText.entrySet();
        Map.Entry<String, Map<String, String>> entr1 = entry.iterator().next();
        Map<String, String> tctxt = entr1.getValue();
        Map.Entry<String, String> topictxt = tctxt.entrySet().iterator().next();
        final int[] currentRow = new int[]{1};

        final XWPFTable table = document.getTables().get(ADDITIONAL_REQ_TABLE_INDEX);
        XWPFTableRow row = table.insertNewTableRow(1);
        row.addNewTableCell().setText(topictxt.getKey());
        row.addNewTableCell().setText(entr1.getKey());
        row.addNewTableCell().setText(topictxt.getValue());
        row.addNewTableCell();
        row.addNewTableCell();

        SW360Utils.getComponentObligations(project).entrySet().stream().forEachOrdered(oblig -> {
            currentRow[0] = currentRow[0] + 1;
            XWPFTableRow crow = table.insertNewTableRow(currentRow[0]);
            crow.addNewTableCell().setText(oblig.getKey().getTitle());
            crow.addNewTableCell().setText(entr1.getKey());
            crow.addNewTableCell().setText(oblig.getKey().getText());
            crow.addNewTableCell().setText(oblig.getValue().fulfilled ? "yes" : "no");
            crow.addNewTableCell().setText(oblig.getValue().getComments());
        });

        setTableBorders(table);
        return table;
    }

    private XmlCursor printLicenseNameHeader(XWPFDocument document, XWPFTable table, String licenseName) throws SW360Exception {
        XmlCursor cursor = table.getCTTbl().newCursor();
        cursor.toEndToken();
        cursor = moveCursor(cursor);

        document.insertNewParagraph(cursor);
        cursor = moveCursor(cursor);
        document.insertNewParagraph(cursor);
        cursor = moveCursor(cursor);

        XWPFParagraph licenseNamePara = document.insertNewParagraph(cursor);
        addFormattedText(licenseNamePara.createRun(), licenseName, FONT_SIZE + 2, true);

        cursor = moveCursor(cursor);
        document.insertNewParagraph(cursor);
        cursor = moveCursor(cursor);
        return cursor;
    }

    private XmlCursor moveCursor(XmlCursor cursor) throws SW360Exception {
        while (cursor.currentTokenType() != XmlCursor.TokenType.START && cursor.hasNextToken()) {
            cursor.toNextToken();
        }

        if (cursor.currentTokenType() != XmlCursor.TokenType.START) {
            throw new SW360Exception("Corrupt template; unable find start token");
        }
        return cursor;
    }

    private void fillReleaseBulletList(XWPFDocument document, Collection<LicenseInfoParsingResult> projectLicenseInfoResults) throws XmlException {
        List<String> releaseList = new ArrayList<>();
        for (LicenseInfoParsingResult result : projectLicenseInfoResults) {
            releaseList.add(getComponentLongName(result));
        }
        addBulletList(document, releaseList, true);
        addPageBreak(document);
    }

    private void fillReleaseDetailList(XWPFDocument document, Collection<LicenseInfoParsingResult> projectLicenseInfoResults, boolean includeObligations,  Map<LicenseNameWithText, Integer> licenseToReferenceId) throws TException {
        addFormattedText(document.createParagraph().createRun(), "Detailed Releases Information", FONT_SIZE + 2, true);
        setText(document.createParagraph().createRun(), "Please note the following license conditions and copyright " +
                "notices applicable to Open Source Software and/or other components (or parts thereof):");
        addNewLines(document, 0);
        Map<String, Set<String>> sortedAcknowledgement = getAcknowledgement(projectLicenseInfoResults);
        for (LicenseInfoParsingResult parsingResult : projectLicenseInfoResults) {
            addReleaseTitle(document, parsingResult);
            addAcknowledgement(document, sortedAcknowledgement.get(getComponentLongName(parsingResult)));
            if (parsingResult.getStatus() == LicenseInfoRequestStatus.SUCCESS) {
                addLicenses(document, parsingResult, includeObligations, licenseToReferenceId);
                addNewLines(document, 1);
                addCopyrights(document, parsingResult);
            } else {
                XWPFRun errorRun = document.createParagraph().createRun();
                String errorText = nullToEmptyString(parsingResult.getMessage());
                String filename = getFilename(parsingResult);
                addFormattedText(errorRun, String.format("Error reading license information: %s", errorText), FONT_SIZE, false, ALERT_COLOR);
                addFormattedText(errorRun, String.format("Source file: %s", filename), FONT_SIZE, false, ALERT_COLOR);
            }
            addNewLines(document, 1);
        }
        addPageBreak(document);
    }

    private void addAcknowledgement(XWPFDocument document, Set<String> acknowledgements) {
        if(CollectionUtils.isNotEmpty(acknowledgements)) {
            XWPFRun ackRun = document.createParagraph().createRun();
            addFormattedText(ackRun, "Acknowledgement", FONT_SIZE, true);
            for (String acknowledgement : acknowledgements) {
                setText(document.createParagraph().createRun(), nullToEmptyString(acknowledgement));
            }
            addNewLines(document, 1);
        }
    }

    private SortedMap<String, Set<String>> getAcknowledgement(
            Collection<LicenseInfoParsingResult> projectLicenseInfoResults) {

        Map<Boolean, List<LicenseInfoParsingResult>> partitionedResults = projectLicenseInfoResults.stream()
                .collect(Collectors.partitioningBy(r -> r.getStatus() == LicenseInfoRequestStatus.SUCCESS));
        List<LicenseInfoParsingResult> goodResults = partitionedResults.get(true);

        return getSortedAcknowledgements(getSortedLicenseInfos(goodResults));

    }

    private void addReleaseTitle(XWPFDocument document, LicenseInfoParsingResult parsingResult) {
        String releaseTitle = getComponentLongName(parsingResult);
        XWPFParagraph releaseTitleParagraph = document.createParagraph();
        releaseTitleParagraph.setStyle(STYLE_HEADING);
        addBookmark(releaseTitleParagraph, releaseTitle, releaseTitle);
        addNewLines(document, 0);
    }

    private void addCopyrights(XWPFDocument document, LicenseInfoParsingResult parsingResult) {
        XWPFRun copyrightTitleRun = document.createParagraph().createRun();
        addFormattedText(copyrightTitleRun, "Copyrights", FONT_SIZE, true);
        for (String copyright : getReleaseCopyrights(parsingResult)) {
            XWPFParagraph copyPara = document.createParagraph();
            copyPara.setSpacingAfter(0);
            setText(copyPara.createRun(), copyright);
        }
    }

    private void addLicenses(XWPFDocument document, LicenseInfoParsingResult parsingResult, boolean includeObligations, Map<LicenseNameWithText, Integer> licenseToReferenceId)
            throws TException {
        XWPFRun licensesTitleRun = document.createParagraph().createRun();
        addNewLines(licensesTitleRun, 1);
        addFormattedText(licensesTitleRun, "Licenses", FONT_SIZE, true);
        StringBuilder licenseNameWithCount = new StringBuilder();
        if (parsingResult.isSetLicenseInfo()) {
            LicenseInfo licenseInfo = parsingResult.getLicenseInfo();
            if (licenseInfo.isSetLicenseNamesWithTexts()) {
                List<LicenseNameWithText> licenseNameWithTexts = licenseInfo.getLicenseNamesWithTexts().stream()
                        .filter(licenseNameWithText -> !LicenseNameWithTextUtils.isEmpty(licenseNameWithText))
                        .sorted(Comparator.comparing(LicenseNameWithText::getLicenseName,String.CASE_INSENSITIVE_ORDER))
                        .collect(Collectors.toList());

                for (LicenseNameWithText licenseNameWithText : licenseNameWithTexts) {
                    XWPFParagraph licensePara = document.createParagraph();
                    licensePara.setSpacingAfter(0);
                    String licenseName = licenseNameWithText.isSetLicenseName() ? licenseNameWithText.getLicenseName()
                            : UNKNOWN_LICENSE_NAME;
                    licenseNameWithCount.append(licenseName).append("(").append(licenseToReferenceId.get(licenseNameWithText)).append(")");
                    addBookmarkHyperLink(licensePara, String.valueOf(licenseToReferenceId.get(licenseNameWithText)),licenseNameWithCount.toString());
                    licenseNameWithCount.setLength(0);
                    if (includeObligations) {
                        addLicenseObligations(document, licenseName);
                    }
                }
            }
        }
    }

    private void addLicenseObligations(XWPFDocument document, String spdxLicense) throws TException {
        List<License> sw360Licenses = getLicenses();
        XWPFRun obligTitleRun = document.createParagraph().createRun();
        addNewLines(obligTitleRun, 0);
        Set<String> obligations = getTodosFromLicenses(spdxLicense, sw360Licenses);
        addFormattedText(obligTitleRun, "Obligations for license " + spdxLicense + ":", FONT_SIZE, true);
        for (String oblig : obligations) {
            XWPFParagraph copyPara = document.createParagraph();
            copyPara.setSpacingAfter(0);
            XWPFRun obligRun = copyPara.createRun();
            setText(obligRun, oblig);
            addNewLines(obligRun, 1);
        }
    }

    private Set<String> getReleaseCopyrights(LicenseInfoParsingResult licenseInfoParsingResult) {
        Set<String> copyrights = Collections.emptySet();
        if (licenseInfoParsingResult.isSetLicenseInfo()) {
            LicenseInfo licenseInfo = licenseInfoParsingResult.getLicenseInfo();
            if (licenseInfo.isSetCopyrights()) {
                copyrights = licenseInfo.getCopyrights();
            }
        }
        return copyrights;
    }

    private String getFilename(LicenseInfoParsingResult licenseInfoParsingResult) {
        return Optional.ofNullable(licenseInfoParsingResult.getLicenseInfo())
                .map(LicenseInfo::getFilenames)
                .flatMap(l -> l.stream().findFirst())
                .orElse(UNKNOWN_FILE_NAME);
    }

    private static Set<String> getTodosFromLicenses(String spdxLicense, List<License> licenses) {
        Set<String> obligations = new HashSet<>();
        if (spdxLicense != null && !spdxLicense.isEmpty() && licenses != null) {
            for (License license : licenses) {
                if (spdxLicense.equalsIgnoreCase(license.getId())) {
                    for (org.eclipse.sw360.datahandler.thrift.licenses.Obligation oblig : license.getObligations()) {
                        obligations.add(oblig.getText());
                    }
                }
            }
            if (obligations.isEmpty()) {
                obligations.add(TODO_DEFAULT_TEXT);
            }
        }
        return obligations;
    }

    private static List<License> getLicenses() throws TException {
        LicenseService.Iface licenseClient = new ThriftClients().makeLicenseClient();
        return licenseClient.getLicenses();
    }

    private void fillLicenseList(XWPFDocument document, Collection<LicenseInfoParsingResult> projectLicenseInfoResults, Map<LicenseNameWithText, Integer> licenseToReferenceId) {
        List<LicenseNameWithText> licenseNameWithTexts = OutputGenerator.getSortedLicenseNameWithTexts(projectLicenseInfoResults);
        XWPFRun licenseHeaderRun = document.createParagraph().createRun();
        addFormattedText(licenseHeaderRun, "License texts", FONT_SIZE + 2, true);
        addNewLines(document, 0);
        StringBuilder licenseNameWithCount = new StringBuilder();
        for (LicenseNameWithText licenseNameWithText : licenseNameWithTexts) {
            XWPFParagraph licenseParagraph = document.createParagraph();
            licenseParagraph.setStyle(STYLE_HEADING);
            String licenseName = licenseNameWithText.isSetLicenseName() ? licenseNameWithText.getLicenseName() : UNKNOWN_LICENSE_NAME;
            licenseNameWithCount.append(licenseToReferenceId.get(licenseNameWithText)).append(": ").append(licenseName);
            addBookmark(licenseParagraph, String.valueOf(licenseToReferenceId.get(licenseNameWithText)), licenseNameWithCount.toString());
            licenseNameWithCount.setLength(0);
            addNewLines(document, 0);
            setText(document.createParagraph().createRun(), nullToEmptyString(licenseNameWithText.getLicenseText()));
            addNewLines(document, 1);
        }
    }

    private void fillOrganisationObligationsTable(XWPFDocument document, Project project) throws TException {
        XWPFTable table = document.getTables().get(COMMON_RULES_TABLE_INDEX);
        final int[] currentRow = new int[]{0};

        SW360Utils.getOrganisationObligations(project).entrySet().stream().forEachOrdered(oblig -> {
            currentRow[0] = currentRow[0] + 1;
            XWPFTableRow row = table.insertNewTableRow(currentRow[0]);
            row.addNewTableCell().setText(oblig.getKey().getText());
            row.addNewTableCell().setText(oblig.getValue().fulfilled ? "yes" : "no");
            row.addNewTableCell().setText(oblig.getValue().getComments());
        });
        setTableBorders(table);
    }

    private void fillProjectSpecificObligationsTable(XWPFDocument document, Project project) throws TException {
        XWPFTable table = document.getTables().get(PROJECT_OBLIGATIONS_TABLE_INDEX);
        final int[] currentRow = new int[] { 0 };

        SW360Utils.getProjectObligations(project).entrySet().stream().forEachOrdered(oblig -> {
            currentRow[0] = currentRow[0] + 1;
            XWPFTableRow row = table.insertNewTableRow(currentRow[0]);
            row.addNewTableCell().setText(oblig.getKey().getText());
            row.addNewTableCell().setText(oblig.getValue().fulfilled ? "yes" : "no");
            row.addNewTableCell().setText(oblig.getValue().getComments());
        });
        setTableBorders(table);
    }

    private void fillLinkedObligations(XWPFDocument document, Map<String, ObligationStatusInfo> obligationsStatus) {
        XWPFTable table = document.getTables().get(OBLIGATION_STATUS_TABLE_INDEX+getNoOfTablesCreated());
        final int[] currentRow = new int[] { 0 };

        if (!obligationsStatus.isEmpty()) {
            obligationsStatus.entrySet().stream().forEach(o -> {
                ObligationStatusInfo osi = o.getValue();
                if (null != osi.getReleases()) {
                    currentRow[0] = currentRow[0] + 1;
                    XWPFTableRow row = table.insertNewTableRow(currentRow[0]);
                    Set<String> releases = osi.getReleases().stream().map(SW360Utils::printName)
                            .collect(Collectors.toSet());
                    row.addNewTableCell().setText(o.getKey());
                    row.addNewTableCell().setText(String.join(", \n", osi.getLicenseIds()));
                    row.addNewTableCell().setText(String.join(", \n", releases));
                    row.addNewTableCell().setText(ThriftEnumUtils.enumToString(osi.getStatus()));
                    row.addNewTableCell().setText(nullToEmptyString(osi.getType()));
                    row.addNewTableCell().setText(nullToEmptyString(osi.getComment()));
                    currentRow[0] = currentRow[0] + 1;
                    XWPFTableRow textRow = table.createRow();
                    textRow.getCell(0).setText(osi.getText());
                    mergeColumns(table, currentRow[0], 0, textRow.getCtRow().sizeOfTcArray() - 1);
                }
            });
        } else {
            currentRow[0] = currentRow[0] + 1;
            XWPFTableRow textRow = table.createRow();
            textRow.getCell(0).setText("No Linked Obligations.");
            mergeColumns(table, currentRow[0], 0, textRow.getCtRow().sizeOfTcArray() - 1);
        }
        setTableBorders(table);
    }

    private static void mergeColumns(XWPFTable table, int row, int fromCol, int toCol) {
        for (int colIndex = fromCol; colIndex <= toCol; colIndex++) {
            XWPFTableCell cell = table.getRow(row).getCell(colIndex);
            if (colIndex == fromCol) {
                // // The first merged cell is set with RESTART merge value
                cell.getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.RESTART);
            } else {
                // Cells which join (merge) the first one, are set with CONTINUE
                cell.getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.CONTINUE);
            }
        }
    }

    private static void setTableBorders(XWPFTable table) {
        table.setInsideVBorder(XWPFBorderType.SINGLE, 0, 0, "00");
        table.setRightBorder(XWPFBorderType.SINGLE, 0, 0, "00");
    }

    public int getNoOfTablesCreated() {
        return noOfTablesCreated;
    }

    public void setNoOfTablesCreated(int noOfTablesCreated) {
        this.noOfTablesCreated = noOfTablesCreated;
    }
}