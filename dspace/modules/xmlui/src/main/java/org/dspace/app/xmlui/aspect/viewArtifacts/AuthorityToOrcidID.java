package org.dspace.app.xmlui.aspect.viewArtifacts;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.dspace.authority.AuthoritySearchService;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.AuthorityValueFinder;
import org.dspace.authority.orcid.OrcidAuthorityValue;
import org.dspace.core.Context;
import org.dspace.utils.DSpace;

import java.sql.SQLException;

/**
 * Created by jonas on 22/09/15.
 */
public class AuthorityToOrcidID {

    private Logger log = Logger.getLogger(AuthorityToOrcidID.class);

    public String getOrcidID(String authorityValue) {
        // Extra check for the authorityValue, should previously be checked in the xsl to see if authoriy even exist in the first place, <xsl:if test="@authority/>, only then call this method
        if (StringUtils.isBlank(authorityValue)) {
            return null;
        }
        AuthorityValueFinder authorityValueFinder = new AuthorityValueFinder();
        Context context = null;
        try {
            context = new Context();
            // If authorityValueFinder can't find an id, a null value will be returned
            AuthorityValue authorityValueInstance = authorityValueFinder.findByUID(context, authorityValue);
            if (authorityValueInstance instanceof OrcidAuthorityValue) {
                if (authorityValueInstance != null) {
                    return ((OrcidAuthorityValue) authorityValueInstance).getOrcid_id();
                }
            }

        } catch (SQLException e) {
            log.error("An exception has occurred while searching for the orcid id", e);
        } finally {
            if (context != null) {
                context.abort();
            }
        }
        return null;

    }

}
