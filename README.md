## Introduction ##
- This custom OSGi module contains a custom Model Listener for CTCollection entity.
- The logic is within onAfterUpdate with a check for model.getStatus() == WorkflowConstants.STATUS_APPROVED to ensure it is only triggered when a Publication is Published.
- The logic identifes the set of Web Content Articles that have been added or modified within the Publication within a specific Site and which have PostLogin structure field true and PostLoginReference structure field populated.
  - It deduplicates for articles that were created and then update or had multiple updates performed within the Publication.
  - It also removes articles that were moved to trask or expired within the Publication.

## Setup ##
- Build and deploy the custom OSGi module to all nodes in the environment:
  - ctcollection-model-listener / com.mw.ctcollection.model.listener-1.0.0.jar

## TODO ##
- Switch the logic to use custom fields rather than structure fields. This will make it structure agnostic as all structures including BASIC_WEB_CONTENT will have the custom fields.

## Notes ##
- This is a ‘proof of concept’ that is being provided ‘as is’ without any support coverage or warranty.
- The implementation was tested locally using Liferay DXP 2024.Q1 LTS.
  - Ensure it is fully tested in a non-production environment before considering deploying to production.
- The custom OSGi module must be deployed to all nodes in the environment.
- The implementation uses a custom OSGi module meaning it is compatible with Liferay DXP Self-Hosted and Liferay PaaS, but is not compatible with Liferay SaaS.
- JDK 11 is expected for both compile time and runtime.
