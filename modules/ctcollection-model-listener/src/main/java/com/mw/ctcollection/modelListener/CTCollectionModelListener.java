package com.mw.ctcollection.modelListener;

import com.liferay.change.tracking.constants.CTConstants;
import com.liferay.change.tracking.model.CTCollection;
import com.liferay.change.tracking.model.CTEntry;
import com.liferay.change.tracking.service.CTEntryLocalService;
import com.liferay.dynamic.data.mapping.storage.DDMFormFieldValue;
import com.liferay.dynamic.data.mapping.storage.DDMFormValues;
import com.liferay.expando.kernel.model.ExpandoBridge;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.service.JournalArticleLocalService;
import com.liferay.portal.kernel.exception.ModelListenerException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.BaseModelListener;
import com.liferay.portal.kernel.model.ClassName;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.ModelListener;
import com.liferay.portal.kernel.security.auth.PrincipalThreadLocal;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.ClassNameLocalService;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.transaction.TransactionCommitCallbackUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(
	immediate = true,
    service = ModelListener.class
)
public class CTCollectionModelListener extends BaseModelListener<CTCollection> {
	
	// Site groupId(s) and / or Asset Library groupId(s)
	private static final long[] GROUP_IDS = {20117, 34537}; // Externalize
	
	private static interface FIELDS {
		static final String POST_LOGIN = "PostLogin";
		static final String POST_LOGIN_REFERENCE = "PostLoginReference";
	}
	
	@Activate
	protected void activate(Map<String, Object> properties) {
        _log.info("Activated...");
	}
	
	private long getJournalArticleClassNameId() {
		ClassName journalArticleClassName = _classNameLocalService.fetchClassName(JournalArticle.class.getCanonicalName());
		
		return journalArticleClassName.getClassNameId();
	}

    @Override
    public void onAfterUpdate(CTCollection originalModel, CTCollection model) throws ModelListenerException {
    	_log.info("onAfterUpdate");
    	
    	boolean run = false;
    	
    	if (originalModel.getStatus() == WorkflowConstants.STATUS_PENDING && model.getStatus() == WorkflowConstants.STATUS_APPROVED) { // Just Published...
    		run = true;
    	}
    	
    	if (!run) return;
    	
    	PermissionChecker permissionChecker = PermissionThreadLocal.getPermissionChecker();
    	
		// Asynchronous so it doesn't prevent the Publishing from completing...
		TransactionCommitCallbackUtil.registerCallback(new Callable<Void>() {
		    @Override
		    public Void call() throws Exception {
		        Executors.newSingleThreadExecutor().submit(() -> {
		            try {
		            	_log.info("Starting...");
		            	
		            	onAfterUpdateJournalArticles(permissionChecker, originalModel, model);
		            	
		            	_log.info("Completed...");
		            } catch (Exception e) {
		                _log.error("Error", e);
		            }
		        });

		        return null;
		    }
		});
    }

    private void onAfterUpdateJournalArticles(PermissionChecker permissionChecker, CTCollection originalModel, CTCollection model) {
    	_log.info("onAfterUpdateJournalArticles");
    	
    	PrincipalThreadLocal.setName(permissionChecker.getUserId());
    	PermissionThreadLocal.setPermissionChecker(permissionChecker);

    	// Assumes Sites have the same Default Locale as the Virtual Instance... 
    	Locale defaultLocale = getDefaultLocale(model.getCompanyId());
    	String defaultLanguageId = LocaleUtil.toLanguageId(defaultLocale);
    	
    	Map<Long, JournalArticle> articlesMap = new HashMap<Long, JournalArticle>();
    	Map<Long, Long> articleDeletedMap = new HashMap<Long, Long>();
    	Map<Long, Long> articleExpiredMap = new HashMap<Long, Long>();

    	List<CTEntry> ctEntriesList = new ArrayList<CTEntry>(_ctEntryLocalService.getCTEntries(model.getCtCollectionId(), getJournalArticleClassNameId()));
    		
		_log.info("Original Journal Articles ctEntries count: " + ctEntriesList.size());
		
		ctEntriesList.sort(Comparator.comparing(CTEntry::getCreateDate).reversed()); // Latest first...
		
		for (CTEntry ctEntry: ctEntriesList) {
			long modelClassPK = ctEntry.getModelClassPK();
			
			int changeType = ctEntry.getChangeType();
			
			JournalArticle journalArticle = _journalArticleLocalService.fetchArticle(modelClassPK);
			
			if (journalArticle == null) continue;
			
			_log.info(CTConstants.getCTChangeTypeLabel(changeType) + " " + journalArticle.getResourcePrimKey() + ", isInTrash: " + journalArticle.isInTrash() + ", isExpired: " + journalArticle.isExpired());
			
			// Only interested in Articles in specific site(s), ignore the rest...
			if (!groupIdsContains(journalArticle.getGroupId())) continue;
			
			if (changeType != CTConstants.CT_CHANGE_TYPE_ADDITION && changeType != CTConstants.CT_CHANGE_TYPE_MODIFICATION && changeType != CTConstants.CT_CHANGE_TYPE_DELETION) {
				continue;
			}
			
			if (changeType == CTConstants.CT_CHANGE_TYPE_DELETION) { // Track so we can discard the deleted ones later...
				articleDeletedMap.put(journalArticle.getResourcePrimKey(), journalArticle.getResourcePrimKey());
				
				_log.info("deleted without trash?");
				
				continue;
			}

			if (journalArticle.isInTrash()) { // Track so we can discard the deleted ones later...
				articleDeletedMap.put(journalArticle.getResourcePrimKey(), journalArticle.getResourcePrimKey());

				continue;
			}
			
			if (journalArticle.isExpired()) { // Track so we can discard the expired ones later...
				articleExpiredMap.put(journalArticle.getResourcePrimKey(), journalArticle.getResourcePrimKey());

				continue;
			}
			
			// Check if latest already included in either map... we only care about the postLogin details of the latest version within this Publications set of changes 
			if (articlesMap.containsKey(journalArticle.getResourcePrimKey()) || articleDeletedMap.containsKey(journalArticle.getResourcePrimKey()) || articleExpiredMap.containsKey(journalArticle.getResourcePrimKey())) continue; 

			getPostLoginReferenceExpandoFields(journalArticle, defaultLocale);
			
			String postLoginReference = getPostLoginReferenceExpandoFields(journalArticle, defaultLocale);
				
			// postLogin true and postLoginReference populated so we care about this change...
			if (Validator.isNotNull(postLoginReference)) articlesMap.put(journalArticle.getResourcePrimKey(), journalArticle);
		}
		
		// The ones in articleDeletedMap shouldn't be in articlesMap but just in case...
		for (Long key : articleDeletedMap.keySet()) {
		    articlesMap.remove(key);
		}

		// The ones in articleExpiredMap shouldn't be in articlesMap but just in case...
		for (Long key : articleExpiredMap.keySet()) {
		    articlesMap.remove(key);
		}
		
		_log.info("Filtered Journal Articles count: " + articlesMap.size());
		
		// The final deduplicated list without deleted articles
		for (Long key : articlesMap.keySet()) {
			JournalArticle journalArticle = _journalArticleLocalService.fetchLatestArticle(key, WorkflowConstants.STATUS_APPROVED);
			
			if (journalArticle == null) {
				_log.info("Journal Article not found: " + key);
			} else {    				
    			_log.info("Journal Article resourcePrimKey: " + journalArticle.getResourcePrimKey() + ", version: " + journalArticle.getVersion() + ", title: " + journalArticle.getTitle(defaultLanguageId) + ", structureId: " + journalArticle.getDDMStructureId() + " with postLoginReference: " + getPostLoginReferenceExpandoFields(journalArticle, defaultLocale));
			}    			
		}
    }
    
	private String getPostLoginReferenceExpandoFields(JournalArticle journalArticle, Locale locale) {
		ExpandoBridge expandoBridge = journalArticle.getExpandoBridge();
		
		if (!expandoBridge.hasAttribute(FIELDS.POST_LOGIN) || !expandoBridge.hasAttribute(FIELDS.POST_LOGIN_REFERENCE)) return null;
		
		String postLoginString = expandoBridge.getAttribute(FIELDS.POST_LOGIN).toString();
		
		if (Validator.isNull(postLoginString) || !postLoginString.equalsIgnoreCase("true")) return null; 
		
		String postLoginReference = expandoBridge.getAttribute(FIELDS.POST_LOGIN_REFERENCE).toString();
		
		if (Validator.isNull(postLoginReference)) return null;
				
		return postLoginReference;
	}    
    
	private String getPostLoginReferenceStructureFields(JournalArticle journalArticle, Locale locale) {
		DDMFormValues ddmFormValues = journalArticle.getDDMFormValues();
		List<DDMFormFieldValue> fieldValues = ddmFormValues.getDDMFormFieldValues();
		
		boolean postLogin = false;
		String postLoginReference = null;

		for (DDMFormFieldValue fieldValue : fieldValues) {
			if (FIELDS.POST_LOGIN.equals(fieldValue.getFieldReference())) {
				if (fieldValue.getValue() == null) return null;
				
				postLogin = Boolean.parseBoolean(fieldValue.getValue().getString(locale));
			}
			
			if ("PostLoginReference".equals(fieldValue.getFieldReference())) {

				if (fieldValue.getValue() == null) return null;
	
				postLoginReference = fieldValue.getValue().getString(locale);
			}
		}
				
		if (!postLogin) return null;
		
		return postLoginReference;
	}
	
	public Locale getDefaultLocale(long companyId){
		
		Company company = _companyLocalService.fetchCompanyById(companyId);
		
		if (company == null) return Locale.UK; //Fall back
		
		Locale locale = null;
		
		try {
			locale = company.getLocale();
		} catch (PortalException e) {
			return Locale.UK; //Fall back
		}
		
		if (locale == null) return Locale.UK; //Fall back
		
		return locale;
	}	
	
	public boolean groupIdsContains(long value) {
	    return Arrays.stream(GROUP_IDS).anyMatch(id -> id == value);
	}	
	
	@Reference
	private ClassNameLocalService _classNameLocalService;
	
	@Reference
	private JournalArticleLocalService _journalArticleLocalService;

	@Reference
	private CompanyLocalService _companyLocalService;
	
	@Reference
	private CTEntryLocalService _ctEntryLocalService;
	
	private static final Log _log = LogFactoryUtil.getLog(CTCollectionModelListener.class);
}