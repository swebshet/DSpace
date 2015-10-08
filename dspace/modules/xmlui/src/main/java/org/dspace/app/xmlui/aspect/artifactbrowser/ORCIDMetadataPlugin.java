package org.dspace.app.xmlui.aspect.artifactbrowser;

import org.dspace.app.xmlui.objectmanager.ItemAdapterMetadataPlugin;
import org.apache.cocoon.environment.Request;
import org.apache.commons.lang.StringUtils;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.AuthorityValueFinder;
import org.dspace.authority.orcid.OrcidAuthorityValue;
import org.dspace.content.Item;
import org.dspace.content.Metadatum;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by tim on 07/10/15.
 */
public class ORCIDMetadataPlugin implements ItemAdapterMetadataPlugin {
	public ORCIDMetadataPlugin() {
		System.out.println("Adaptor metadata plugin ORCID");
	}

	@Override
	public Collection<? extends Metadatum> generateMetadataForItem(Request request,Context context,Item item) {
		ConfigurationService confman = new DSpace().getConfigurationService();
		String authorConfField = "authority.author.indexer.field.";

		List<Metadatum> values = new ArrayList<>();
		List<String> authorFields = new ArrayList<>();

		for(int counter = 1; confman.getProperty(authorConfField.concat(String.valueOf(counter))) != null; counter++) {
			authorFields.add(confman.getProperty(authorConfField.concat(String.valueOf(counter))));
		}

		for(String authorField : authorFields) {
			Metadatum authors[] = item.getMetadataByMetadataString(authorField);

			for(Metadatum author : authors) {
				if(author != null) {
					String authority = author.authority;
					Metadatum md = new Metadatum();
					md.value = getOrcidID(authority, context);
					md.schema = "atmire";
					md.element = "orcid";
					md.qualifier = "id";
					md.authority = authority;

					if(StringUtils.isNotBlank(md.value)) {
						values.add(md);
					}
				}
			}
		}

		return values;
	}

	private String getOrcidID(String authorityValue, Context context) {
		if (StringUtils.isBlank(authorityValue)) {
			return null;
		}

		AuthorityValueFinder authorityValueFinder = new AuthorityValueFinder();

		// If authorityValueFinder can't find an id, a null value will be returned
		AuthorityValue authorityValueInstance = authorityValueFinder.findByUID(context, authorityValue);

		if (authorityValueInstance instanceof OrcidAuthorityValue) {
			return ((OrcidAuthorityValue) authorityValueInstance).getOrcid_id();
		}

		return null;
	}
}
