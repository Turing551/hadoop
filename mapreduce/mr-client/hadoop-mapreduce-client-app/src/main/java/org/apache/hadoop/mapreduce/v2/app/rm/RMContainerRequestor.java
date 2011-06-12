/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.rm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.app.AppContext;
import org.apache.hadoop.mapreduce.v2.app.client.ClientService;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.AMResponse;
import org.apache.hadoop.yarn.api.records.ApplicationStatus;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;

/**
 * Keeps the data structures to send container requests to RM.
 */
public abstract class RMContainerRequestor extends RMCommunicator {
  
  private static final Log LOG = LogFactory.getLog(RMContainerRequestor.class);
  static final String ANY = "*";

  private int lastResponseID;
  private Resource availableResources;

  private final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);
  //Key -> Priority
  //Value -> Map
  //Key->ResourceName (e.g., hostname, rackname, *)
  //Value->Map
  //Key->Resource Capability
  //Value->ResourceReqeust
  private final Map<Priority, Map<String, Map<Resource, ResourceRequest>>>
  remoteRequestsTable =
      new TreeMap<Priority, Map<String, Map<Resource, ResourceRequest>>>();

  private final Set<ResourceRequest> ask = new TreeSet<ResourceRequest>();
  private final Set<Container> release = new TreeSet<Container>(); 

  public RMContainerRequestor(ClientService clientService, AppContext context) {
    super(clientService, context);
  }

  static class ContainerRequest {
    final TaskAttemptId attemptID;
    final Resource capability;
    final String[] hosts;
    final String[] racks;
    //final boolean earlierAttemptFailed;
    final Priority priority;
    public ContainerRequest(ContainerRequestEvent event, Priority priority) {
      this.attemptID = event.getAttemptID();
      this.capability = event.getCapability();
      this.hosts = event.getHosts();
      this.racks = event.getRacks();
      //this.earlierAttemptFailed = event.getEarlierAttemptFailed();
      this.priority = priority;
    }
  }
  
  protected abstract void heartbeat() throws Exception;

  protected List<Container> makeRemoteRequest() throws YarnRemoteException {
    ApplicationStatus status = recordFactory
        .newRecordInstance(ApplicationStatus.class);
    status.setApplicationId(applicationId);
    status.setResponseId(lastResponseID);

    AllocateRequest allocateRequest = recordFactory
        .newRecordInstance(AllocateRequest.class);
    allocateRequest.setApplicationStatus(status);
    allocateRequest.addAllAsks(new ArrayList<ResourceRequest>(ask));
    allocateRequest.addAllReleases(new ArrayList<Container>(release));
    AllocateResponse allocateResponse = scheduler.allocate(allocateRequest);
    AMResponse response = allocateResponse.getAMResponse();
    lastResponseID = response.getResponseId();
    List<Container> allContainers = response.getContainerList();
    availableResources = response.getAvailableResources();
    ask.clear();
    release.clear();

    LOG.info("getResources() for " + applicationId + ":" + " ask="
        + ask.size() + " release= " + release.size() + " recieved="
        + allContainers.size()
        + " resourcelimit=" + availableResources);
    return allContainers;
  }

  protected Resource getAvailableResources() {
    return availableResources;
  }
  
  protected void addContainerReq(ContainerRequest req) {
    // Create resource requests
    for (String host : req.hosts) {
      // Data-local
      addResourceRequest(req.priority, host, req.capability);
    }

    // Nothing Rack-local for now
    for (String rack : req.racks) {
      addResourceRequest(req.priority, rack, req.capability);
    }

    // Off-switch
    addResourceRequest(req.priority, ANY, req.capability); 
  }

  protected void decContainerReq(ContainerRequest req) {
    // Update resource requests
    for (String hostName : req.hosts) {
      decResourceRequest(req.priority, hostName, req.capability);
    }
    
    for (String rack : req.racks) {
      decResourceRequest(req.priority, rack, req.capability);
    }
   
    decResourceRequest(req.priority, ANY, req.capability);
  }

  private void addResourceRequest(Priority priority, String resourceName,
      Resource capability) {
    Map<String, Map<Resource, ResourceRequest>> remoteRequests =
      this.remoteRequestsTable.get(priority);
    if (remoteRequests == null) {
      remoteRequests = new HashMap<String, Map<Resource, ResourceRequest>>();
      this.remoteRequestsTable.put(priority, remoteRequests);
      LOG.info("Added priority=" + priority);
    }
    Map<Resource, ResourceRequest> reqMap = remoteRequests.get(resourceName);
    if (reqMap == null) {
      reqMap = new HashMap<Resource, ResourceRequest>();
      remoteRequests.put(resourceName, reqMap);
    }
    ResourceRequest remoteRequest = reqMap.get(capability);
    if (remoteRequest == null) {
      remoteRequest = recordFactory.newRecordInstance(ResourceRequest.class);
      remoteRequest.setPriority(priority);
      remoteRequest.setHostName(resourceName);
      remoteRequest.setCapability(capability);
      remoteRequest.setNumContainers(0);
      reqMap.put(capability, remoteRequest);
    }
    remoteRequest.setNumContainers(remoteRequest.getNumContainers() + 1);

    // Note this down for next interaction with ResourceManager
    ask.add(remoteRequest);
    LOG.info("addResourceRequest:" + " applicationId=" + applicationId.getId()
        + " priority=" + priority.getPriority() + " resourceName=" + resourceName
        + " numContainers=" + remoteRequest.getNumContainers() + " #asks="
        + ask.size());
  }

  private void decResourceRequest(Priority priority, String resourceName,
      Resource capability) {
    Map<String, Map<Resource, ResourceRequest>> remoteRequests =
      this.remoteRequestsTable.get(priority);
    Map<Resource, ResourceRequest> reqMap = remoteRequests.get(resourceName);
    ResourceRequest remoteRequest = reqMap.get(capability);

    LOG.info("BEFORE decResourceRequest:" + " applicationId=" + applicationId.getId()
        + " priority=" + priority.getPriority() + " resourceName=" + resourceName
        + " numContainers=" + remoteRequest.getNumContainers() + " #asks="
        + ask.size());

    remoteRequest.setNumContainers(remoteRequest.getNumContainers() -1);
    if (remoteRequest.getNumContainers() == 0) {
      reqMap.remove(capability);
      if (reqMap.size() == 0) {
        remoteRequests.remove(resourceName);
      }
      if (remoteRequests.size() == 0) {
        remoteRequestsTable.remove(priority);
      }
      //remove from ask if it may have
      ask.remove(remoteRequest);
    } else {
      ask.add(remoteRequest);//this will override the request if ask doesn't
      //already have it.
    }

    LOG.info("AFTER decResourceRequest:" + " applicationId="
             + applicationId.getId() + " priority=" + priority.getPriority()
             + " resourceName=" + resourceName + " numContainers="
             + remoteRequest.getNumContainers() + " #asks=" + ask.size());
  }

  protected void release(Container container) {
    release.add(container);
  }
  
}
