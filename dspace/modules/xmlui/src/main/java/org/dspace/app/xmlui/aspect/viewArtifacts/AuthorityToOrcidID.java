package org.dspace.app.xmlui.aspect.viewArtifacts;

import org.apache.solr.client.solrj.SolrQuery;
import org.dspace.authority.AuthoritySearchService;
import org.dspace.utils.DSpace;

/**
 * Created by jonas on 22/09/15.
 */
public class AuthorityToOrcidID {

    public String getOrcidID(String authorityValue) throws Exception {

        SolrQuery queryArgs = new SolrQuery();
        queryArgs.setQuery("*:*");
        queryArgs.addFilterQuery("id:" + authorityValue);

        if (getSearchService().search(queryArgs).getResults().size() > 0) {
            try {
                return (String) getSearchService().search(queryArgs).getResults().get(0).get("orcid_id");
            } catch (Exception e) {
                return null;
            }

        }
        return null;
    }

    public static AuthoritySearchService getSearchService() {
        DSpace dspace = new DSpace();

        org.dspace.kernel.ServiceManager manager = dspace.getServiceManager();

        return manager.getServiceByName(AuthoritySearchService.class.getName(), AuthoritySearchService.class);
    }

}
