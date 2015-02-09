////////////////////////////////////////////////////////////////////////
//
//     Copyright (c) 2009-2015 Denim Group, Ltd.
//
//     The contents of this file are subject to the Mozilla Public License
//     Version 2.0 (the "License"); you may not use this file except in
//     compliance with the License. You may obtain a copy of the License at
//     http://www.mozilla.org/MPL/
//
//     Software distributed under the License is distributed on an "AS IS"
//     basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
//     License for the specific language governing rights and limitations
//     under the License.
//
//     The Original Code is ThreadFix.
//
//     The Initial Developer of the Original Code is Denim Group, Ltd.
//     Portions created by Denim Group, Ltd. are Copyright (C)
//     Denim Group, Ltd. All Rights Reserved.
//
//     Contributor(s): Denim Group, Ltd.
//
////////////////////////////////////////////////////////////////////////
package com.denimgroup.threadfix.service.merge;

/**
 * Created by mcollins on 2/5/15.
 */

import com.denimgroup.threadfix.data.dao.ApplicationChannelDao;
import com.denimgroup.threadfix.data.dao.ApplicationDao;
import com.denimgroup.threadfix.data.dao.FindingDao;
import com.denimgroup.threadfix.data.dao.VulnerabilityDao;
import com.denimgroup.threadfix.data.entities.*;
import com.denimgroup.threadfix.logging.SanitizedLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.denimgroup.threadfix.CollectionUtils.*;

@Component
public class RemappingService {

    SanitizedLogger LOG = new SanitizedLogger(RemappingService.class);

    @Autowired
    ApplicationDao applicationDao;
    @Autowired
    FindingDao findingDao;
    @Autowired
    ApplicationChannelDao applicationChannelDao;
    @Autowired
    VulnerabilityDao vulnerabilityDao;

    public void remapFindings(ChannelVulnerability vulnerability) {
        List<Application> applications = applicationDao.retrieveAllActive();

        for (Application application : applications) {
            remapFindings(application, vulnerability);
        }
    }

    private void remapFindings(Application application, ChannelVulnerability type) {

        Integer id = type.getChannelType().getId();

        ApplicationChannel channel = null;

        for (ApplicationChannel appChannel : application.getChannelList()) {
            ChannelType channelType = appChannel.getChannelType();
            if (channelType.getId().equals(id)) {
                channel = appChannel;
            } else if (channelType.getName().equals("SSVL") && type.getChannelType().getName().equals("Manual")) {
                channel = appChannel;
            }
        }

        VulnerabilityCache
                cache = new VulnerabilityCache(application.getVulnerabilities()),
                newCache = new VulnerabilityCache();

        List<Vulnerability>
                newVulnerabilities = list(),
                vulnerabilitiesToUpdate = list();

        List<Finding> findings = findingDao.retrieveByChannelVulnerabilityAndApplication(type.getId(), application.getId());

        LOG.info("Got " + findings.size() + " results for this channel vulnerability.");

        for (Finding finding : findings) {

            attemptToAddFromCache(cache, finding);
            attemptToAddFromCache(newCache, finding);

            if (finding.getVulnerability() == null) {
                Vulnerability parse = VulnerabilityParser.parse(finding);

                newVulnerabilities.add(parse);
                newCache.add(parse);
            }

            vulnerabilitiesToUpdate.add(finding.getVulnerability());
        }

        for (Vulnerability newVulnerability : newVulnerabilities) {
            application.addVulnerability(newVulnerability);
        }

        for (Vulnerability vulnerability : vulnerabilitiesToUpdate) {
            fixStateAndMappings(channel, vulnerability);
            vulnerabilityDao.saveOrUpdate(vulnerability);
        }
    }

    enum Event {
        OLD_FINDING, NEW_FINDING, NEW_FINDING_REPEAT, OLD_FINDING_REPEAT,
        CLOSE, REOPEN, SCAN_WITH_NO_DATA;

        static Set<Event> closedEvents = set(CLOSE, SCAN_WITH_NO_DATA);

        static boolean isOpen(Event event) {
            return !closedEvents.contains(event);
        }
    }

    private void fixStateAndMappings(ApplicationChannel channel,
                                     Vulnerability newVulnerability) {
        setFirstFindingForVuln(newVulnerability);

        // collectMaps
        Map<Calendar, Event> scannerEventMap = getScannerEventMap(newVulnerability, channel);
        Map<Calendar, Scan> scanTimeMap = newMap();
        for (Scan scan : channel) {
            scanTimeMap.put(scan.getImportTime(), scan);
        }

        Map<Calendar, ScanCloseVulnerabilityMap> closeMap = getCloseMap(newVulnerability);
        Map<Calendar, ScanReopenVulnerabilityMap> reopenMap = getReopenMap(newVulnerability);

        List<Calendar> allDates = listFrom(scannerEventMap.keySet());

        Collections.sort(allDates);

        processTimeline(newVulnerability, scannerEventMap, scanTimeMap, allDates, closeMap, reopenMap);
    }

    private void processTimeline(Vulnerability newVulnerability,
                                 Map<Calendar, Event> scannerEventMap,
                                 Map<Calendar, Scan> scanTimeMap,
                                 List<Calendar> allDates,
                                 Map<Calendar, ScanCloseVulnerabilityMap> closeMap,
                                 Map<Calendar, ScanReopenVulnerabilityMap> reopenMap) {
        boolean isOpen = newVulnerability.isActive();
        Boolean shouldBeOpen = null;
        Calendar lastActionDate = null;

        ScanCloseVulnerabilityMap lastMap = null;

        for (Calendar date : allDates) {

            Event event = scannerEventMap.get(date);

            System.out.println(event);

            boolean openEvent = Event.isOpen(event);

            if (shouldBeOpen == null) {
                shouldBeOpen = openEvent;
            }

            if (closeMap.containsKey(date) && openEvent) {
                lastMap = closeMap.get(date);
            } else if (!openEvent && lastMap != null) {
                lastMap.getScan().getScanCloseVulnerabilityMaps().remove(lastMap);
                Scan scan = scanTimeMap.get(date);
                lastMap.setScan(scan);
                if (scan.getScanCloseVulnerabilityMaps() == null) {
                    scan.setScanCloseVulnerabilityMaps(listOf(ScanCloseVulnerabilityMap.class));
                }
                scan.getScanCloseVulnerabilityMaps().add(lastMap);

                lastMap = null;
            }

            if (openEvent != shouldBeOpen) {
                lastActionDate = date;
                shouldBeOpen = openEvent;
            }
        }

        if (shouldBeOpen != null && shouldBeOpen != isOpen) {
            if (shouldBeOpen) {
                newVulnerability.openVulnerability(lastActionDate);
            } else {
                newVulnerability.closeVulnerability(scanTimeMap.get(lastActionDate), lastActionDate);
            }
        }
    }

    // there has to be a better algorithm for this
    // or maybe we can sidestep by setting this in a different spot?
    private void setFirstFindingForVuln(Vulnerability newVulnerability) {
        if (newVulnerability.getFindings().size() == 0) {
            return;
        }

        Finding currentOriginalFinding = null;
        Finding oldestFinding = null;
        Calendar oldestDate = null;

        for (Finding finding : newVulnerability.getFindings()) {
            if (oldestDate == null || finding.getScan().getImportTime().before(oldestDate)) {
                oldestDate = finding.getScan().getImportTime();
                oldestFinding = finding;
            }

            if (finding.isFirstFindingForVuln()) {
                currentOriginalFinding = finding;
            }
        }

        if (currentOriginalFinding != oldestFinding) {
            if (currentOriginalFinding != null) {
                currentOriginalFinding.setFirstFindingForVuln(false);
            }

            oldestFinding.setFirstFindingForVuln(true);
        }
    }

    private Map<Calendar, Event> getScannerEventMap(Vulnerability newVulnerability,
                                                    ApplicationChannel applicationChannel) {
        Map<Calendar, Event> scannerEvents = newMap();

        for (Finding finding : newVulnerability.getFindings()) {
            scannerEvents.put(finding.getScan().getImportTime(), Event.OLD_FINDING);

            if (finding.getScanRepeatFindingMaps() != null) {
                for (ScanRepeatFindingMap repeatMap : finding.getScanRepeatFindingMaps()) {
                    scannerEvents.put(repeatMap.getScan().getImportTime(), Event.NEW_FINDING_REPEAT);
                }
            }
        }

        for (Scan scan : applicationChannel) {
            if (!scannerEvents.containsKey(scan.getImportTime())) {
                scannerEvents.put(scan.getImportTime(), Event.SCAN_WITH_NO_DATA);
            }
        }

        return scannerEvents;
    }

    private Map<Calendar, ScanCloseVulnerabilityMap> getCloseMap(Vulnerability newVulnerability) {
        Map<Calendar, ScanCloseVulnerabilityMap> scannerEvents = newMap();

        if (newVulnerability.getScanCloseVulnerabilityMaps() != null) {
            for (ScanCloseVulnerabilityMap closeMap : newVulnerability.getScanCloseVulnerabilityMaps()) {
                scannerEvents.put(closeMap.getScan().getImportTime(), closeMap);
            }
        }

        return scannerEvents;
    }

    private Map<Calendar, ScanReopenVulnerabilityMap> getReopenMap(Vulnerability newVulnerability) {
        Map<Calendar, ScanReopenVulnerabilityMap> scannerEvents = newMap();

        if (newVulnerability.getScanReopenVulnerabilityMaps() != null) {
            for (ScanReopenVulnerabilityMap reopenMap : newVulnerability.getScanReopenVulnerabilityMaps()) {
                scannerEvents.put(reopenMap.getScan().getImportTime(), reopenMap);
            }
        }

        return scannerEvents;
    }

    private void attemptToAddFromCache(VulnerabilityCache cache, Finding finding) {
        Iterable<Vulnerability> possibilities = cache.getPossibilities(finding);

        if (possibilities.iterator().hasNext()) { // not empty

            FindingMatcher findingMatcher = new FindingMatcher(finding.getScan());

            for (Vulnerability vulnerability : possibilities) {

                if (findingMatcher.doesMatch(finding, vulnerability)) {
                    VulnerabilityParser.addToVuln(vulnerability, finding);
                }
            }
        }
    }
}
