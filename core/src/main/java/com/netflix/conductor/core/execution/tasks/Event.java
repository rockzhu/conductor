/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * 
 */
package com.netflix.conductor.core.execution.tasks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.core.events.EventQueues;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.core.execution.ParametersUtils;
import com.netflix.conductor.core.execution.WorkflowExecutor;

/**
 * @author Viren
 *
 */
public class Event extends WorkflowSystemTask {
	
	private static Logger logger = LoggerFactory.getLogger(Event.class);
	
	private ObjectMapper om = new ObjectMapper();
	
	private ParametersUtils pu = new ParametersUtils();
	
	public static final String NAME = "EVENT";
	
	public Event() {
		super(NAME);
	}
	
	@Override
	public void start(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		
		Map<String, Object> payload = new HashMap<>();
		payload.putAll(task.getInputData());
		payload.put("workflowInstanceId", workflow.getWorkflowId());
		payload.put("workflowType", workflow.getWorkflowType());
		payload.put("workflowVersion", workflow.getVersion());
		payload.put("correlationId", workflow.getCorrelationId());
		
		String payloadJson = om.writeValueAsString(payload);
		Message message = new Message(task.getTaskId(), payloadJson, task.getTaskId());
		ObservableQueue queue = getQueue(workflow, task);
		if(queue != null) {
			queue.publish(Arrays.asList(message));
			task.getOutputData().putAll(payload);
			task.setStatus(Status.COMPLETED);
		} else {
			task.setReasonForIncompletion("No queue found to publish.");
			task.setStatus(Status.FAILED);
		}
	}

	@Override
	public boolean execute(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		return false;
	}
	
	@Override
	public void cancel(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		Message message = new Message(task.getTaskId(), null, task.getTaskId());
		getQueue(workflow, task).ack(Arrays.asList(message));
	}

	@VisibleForTesting
	ObservableQueue getQueue(Workflow workflow, Task task) {
		
		String sinkValueRaw = "" + task.getInputData().get("sink");
		Map<String, Object> input = new HashMap<>();
		input.put("sink", sinkValueRaw);
		Map<String, Object> replaced = pu.getTaskInputV2(input, workflow, task.getTaskId(), null);
		String sinkValue = (String)replaced.get("sink");
		
		String queueName = sinkValue;

		if(sinkValue.startsWith("conductor")) {
			
			if("conductor".equals(sinkValue)) {
				
				queueName = sinkValue + ":" + workflow.getWorkflowType() + ":" + task.getReferenceTaskName();
				
			} else if(sinkValue.startsWith("conductor:")) {
				
				queueName = sinkValue.replaceAll("conductor:", "");
				queueName = "conductor:" + workflow.getWorkflowType() + ":" + queueName;
				
			} else {
				task.setStatus(Status.FAILED);
				task.setReasonForIncompletion("Invalid / Unsupported sink specified: " + sinkValue);
				return null;
			}
			
		}

		task.getOutputData().put("event_produced", queueName);
		
		try {
			return EventQueues.getQueue(queueName, true);
		}catch(Exception e) {
			logger.error(e.getMessage(), e);
			task.setStatus(Status.FAILED);
			task.setReasonForIncompletion("Error when trying to access the specified queue/topic: " + sinkValue + ", error: " + e.getMessage());
			return null;			
		}
		
	}
	
	@Override
	public boolean isAsync() {
		return false;
	}
}
