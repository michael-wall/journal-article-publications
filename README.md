## Introduction ##
- This custom OSGi module contains a custom Model Listener for CTCollection entity.
- The logic is triggered asnynchronously from within the onAfterUpdate event after a check for model.getStatus() == WorkflowConstants.STATUS_APPROVED to ensure it is only triggered when a Publication is Published.
- The logic identifies the relevant set of unique Web Content Articles that have been added or modified within the Publication based on the following logic:
  - They are within a specified set of Sites and / or Asset Libraries.
  - They have the PostLogin custom field set to true and the PostLoginReference custom field populated.
  - Deduplicates articles that were created and then updated within the Publication or existing articles that had multiple updates performed within the Publication.
  - Excludes articles that were moved to trash or that were expired within the Publication.

## Setup ##
- Create the following Web Content Article Custom fields:
  - Name / Key: PostLogin
     - Type: True / False
     - Default Value: False
     - Localize Field Name Disabled
     - Searchable as Keyword Enabled
  - Name / Key: PostLoginReference
    - Type: Input Field / Text
     - Localize Field Name Disabled
     - Searchable as Keyword Enabled
- Build and deploy the custom OSGi module to all nodes in the environment:
  - ctcollection-model-listener / com.mw.ctcollection.model.listener-1.0.0.jar
 
## TODO ##
- Externalize the Site Group Id(s) / Asset Library Group Id(s) that are currently hardcoded in CTCollectionModelListener GROUP_IDS.

## Notes ##
- This is a ‘proof of concept’ that is being provided ‘as is’ without any support coverage or warranty.
- The implementation was tested locally using Liferay DXP 2024.Q1 LTS.
  - Ensure it is fully tested in a non-production environment before considering deploying to production.
- The custom OSGi module must be deployed to all nodes in the environment.
- The implementation uses a custom OSGi module meaning it is compatible with Liferay DXP Self-Hosted and Liferay PaaS, but is not compatible with Liferay SaaS.
- JDK 11 is expected for both compile time and runtime.
