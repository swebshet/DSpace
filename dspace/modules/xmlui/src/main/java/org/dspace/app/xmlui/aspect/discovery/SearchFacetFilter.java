
/**
 * Created by jonas on 03/09/15.
 */
/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.xmlui.aspect.discovery;

import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.ProcessingException;
import org.apache.cocoon.ResourceNotFoundException;
import org.apache.cocoon.caching.CacheableProcessingComponent;
import org.apache.cocoon.environment.ObjectModelHelper;
import org.apache.cocoon.environment.Request;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.cocoon.environment.http.HttpEnvironment;
import org.apache.cocoon.util.HashUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.excalibur.source.SourceValidity;
import org.apache.log4j.Logger;
import org.dspace.app.util.Util;
import org.dspace.app.xmlui.cocoon.AbstractDSpaceTransformer;
import org.dspace.app.xmlui.utils.*;
import org.dspace.app.xmlui.wing.Message;
import org.dspace.app.xmlui.wing.WingException;
import org.dspace.app.xmlui.wing.element.*;
import org.dspace.authorize.AuthorizeException;
import org.dspace.browse.*;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.discovery.*;
import org.dspace.discovery.configuration.DiscoveryConfiguration;
import org.dspace.discovery.configuration.DiscoveryConfigurationParameters;
import org.dspace.discovery.configuration.DiscoverySortConfiguration;
import org.dspace.discovery.configuration.DiscoverySortFieldConfiguration;
import org.dspace.handle.HandleManager;
import org.dspace.sort.SortException;
import org.dspace.sort.SortOption;
import org.dspace.utils.DSpace;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.*;
import java.util.List;

/**
 * Filter which displays facets on which a user can filter his discovery search
 *
 * @author Kevin Van de Velde (kevin at atmire dot com)
 * @author Mark Diggory (markd at atmire dot com)
 * @author Ben Bosman (ben at atmire dot com)
 */
public class SearchFacetFilter extends AbstractDSpaceTransformer implements CacheableProcessingComponent {

    private static final Logger log = Logger.getLogger(BrowseFacet.class);

    private static final Message T_dspace_home = message("xmlui.general.dspace_home");

    private static final int[] RESULTS_PER_PAGE_PROGRESSION = {5, 10, 20, 40, 60, 80, 100};

    /**
     * The cache of recently submitted items
     */
    protected DiscoverResult queryResults;
    /**
     * Cached validity object
     */
    protected SourceValidity validity;
    /** Cached UI parameters, results and messages */
    private BrowseParams userParams;

    private BrowseInfo browseInfo;
    /**
     * Cached query arguments
     */
    protected DiscoverQuery queryArgs;

    private int DEFAULT_PAGE_SIZE = getParameterRpp();


    private SearchService searchService = null;
    private static final Message T_go = message("xmlui.general.go");
    private static final Message T_rpp = message("xmlui.Discovery.AbstractSearch.rpp");

    public SearchFacetFilter() {

        DSpace dspace = new DSpace();
        searchService = dspace.getServiceManager().getServiceByName(SearchService.class.getName(),SearchService.class);

    }

    @Override
    public void setup(SourceResolver resolver, Map objectModel, String src, Parameters parameters) throws ProcessingException, SAXException, IOException {
        super.setup(resolver, objectModel, src, parameters);

        Request request = ObjectModelHelper.getRequest(objectModel);
        String facetField = request.getParameter(SearchFilterParam.FACET_FIELD);

        if(StringUtils.isBlank(facetField))
        {
            throw new BadRequestException("Invalid " + SearchFilterParam.FACET_FIELD + " parameter");
        }
    }

    /**
     * Generate the unique caching key.
     * This key must be unique inside the space of this component.
     */
    public Serializable getKey() {
        try {
            DSpaceObject dso = HandleUtil.obtainHandle(objectModel);

            if (dso == null)
            {
                return "0";
            }

            return HashUtil.hash(dso.getHandle());
        }
        catch (SQLException sqle) {
            // Ignore all errors and just return that the component is not
            // cachable.
            return "0";
        }
    }

    /**
     * Generate the cache validity object.
     * <p/>
     * The validity object will include the collection being viewed and
     * all recently submitted items. This does not include the community / collection
     * hierarchy, when this changes they will not be reflected in the cache.
     */
    public SourceValidity getValidity() {
        if (this.validity == null) {

            try {
                DSpaceValidity validity = new DSpaceValidity();

                DSpaceObject dso = getScope();

                if (dso != null) {
                    // Add the actual collection;
                    validity.add(dso);
                }

                // add recently submitted items, serialize solr query contents.
                DiscoverResult response = getQueryResponse(dso);

                validity.add("numFound:" + response.getDspaceObjects().size());

                for (DSpaceObject resultDso : queryResults.getDspaceObjects()) {
                    validity.add(resultDso);
                }

                for (String facetField : queryResults.getFacetResults().keySet()) {
                    validity.add(facetField);

                    java.util.List<DiscoverResult.FacetResult> facetValues = queryResults.getFacetResults().get(facetField);
                    for (DiscoverResult.FacetResult facetValue : facetValues) {
                        validity.add(facetField + facetValue.getAsFilterQuery() + facetValue.getCount());
                    }
                }


                this.validity = validity.complete();
            }
            catch (Exception e) {
                // Just ignore all errors and return an invalid cache.
            }

            //TODO: dependent on tags as well :)
        }
        return this.validity;
    }

    /**
     * Get the recently submitted items for the given community or collection.
     *
     * @param scope The collection.
     */
    protected DiscoverResult getQueryResponse(DSpaceObject scope) {


        Request request = ObjectModelHelper.getRequest(objectModel);

        if (queryResults != null)
        {
            return queryResults;
        }

        queryArgs = new DiscoverQuery();

        //Make sure we add our default filters
        DiscoveryConfiguration discoveryConfiguration = SearchUtils.getDiscoveryConfiguration(scope);
        List<String> defaultFilterQueries = discoveryConfiguration.getDefaultFilterQueries();
        queryArgs.addFilterQueries(defaultFilterQueries.toArray(new String[defaultFilterQueries.size()]));


        queryArgs.setQuery(((request.getParameter("query") != null && !"".equals(request.getParameter("query").trim())) ? request.getParameter("query") : null));
//        queryArgs.setQuery("search.resourcetype:" + Constants.ITEM);
        queryArgs.setDSpaceObjectFilter(Constants.ITEM);

        queryArgs.setMaxResults(getParameterRpp());

        queryArgs.addFilterQueries(DiscoveryUIUtils.getFilterQueries(request, context));


        //Set the default limit to 11
        //query.setFacetLimit(11);
        queryArgs.setFacetMinCount(1);

        int offset = RequestUtils.getIntParameter(request, SearchFilterParam.OFFSET);
        if (offset == -1)
        {
            offset = 0;
        }
        queryArgs.setFacetOffset(offset);

        //We add +1 so we can use the extra one to make sure that we need to show the next page
//        queryArgs.setFacetLimit();

        String facetField = request.getParameter(SearchFilterParam.FACET_FIELD);
        DiscoverFacetField discoverFacetField;
        if(request.getParameter(SearchFilterParam.STARTS_WITH) != null)
        {
            discoverFacetField = new DiscoverFacetField(facetField, DiscoveryConfigurationParameters.TYPE_TEXT, DEFAULT_PAGE_SIZE + 1, DiscoveryConfigurationParameters.SORT.COUNT, request.getParameter(SearchFilterParam.STARTS_WITH).toLowerCase());
        }else{
            discoverFacetField = new DiscoverFacetField(facetField, DiscoveryConfigurationParameters.TYPE_TEXT, DEFAULT_PAGE_SIZE + 1, DiscoveryConfigurationParameters.SORT.COUNT);
        }


        queryArgs.addFacetField(discoverFacetField);


        try {
            queryResults = searchService.search(context, scope, queryArgs);
        } catch (SearchServiceException e) {
            log.error(e.getMessage(), e);
        }

        return queryResults;
    }

    /**
     * Add a page title and trail links.
     */
    public void addPageMeta(PageMeta pageMeta) throws SAXException, WingException, SQLException, IOException, AuthorizeException {
        Request request = ObjectModelHelper.getRequest(objectModel);
        String facetField = request.getParameter(SearchFilterParam.FACET_FIELD);

        pageMeta.addMetadata("title").addContent(message("xmlui.Discovery.AbstractSearch.type_" + facetField));


        pageMeta.addTrailLink(contextPath + "/", T_dspace_home);

        DSpaceObject dso = HandleUtil.obtainHandle(objectModel);
        if ((dso instanceof Collection) || (dso instanceof Community)) {
            HandleUtil.buildHandleTrail(dso, pageMeta, contextPath, true);
        }

        pageMeta.addTrail().addContent(message("xmlui.Discovery.AbstractSearch.type_" + facetField));
    }

    private BrowseParams getUserParams() throws SQLException, UIException, ResourceNotFoundException, IllegalArgumentException {

        if (this.userParams != null)
        {
            return this.userParams;
        }

        Context context = ContextUtil.obtainContext(objectModel);
        Request request = ObjectModelHelper.getRequest(objectModel);

        BrowseParams params = new BrowseParams();

        params.month = request.getParameter(BrowseParams.MONTH);
        params.year = request.getParameter(BrowseParams.YEAR);
        params.etAl = RequestUtils.getIntParameter(request, BrowseParams.ETAL);

        params.scope = new BrowserScope(context);

        // Are we in a community or collection?
        DSpaceObject dso = HandleUtil.obtainHandle(objectModel);
        if (dso instanceof Community)
        {
            params.scope.setCommunity((Community) dso);
        }
        if (dso instanceof Collection)
        {
            params.scope.setCollection((Collection) dso);
        }

        try
        {
            String type   = request.getParameter(BrowseParams.TYPE);
            int    sortBy = RequestUtils.getIntParameter(request, BrowseParams.SORT_BY);

            if(!request.getParameters().containsKey("type"))
            {
                // default to first browse index.
                String defaultBrowseIndex = ConfigurationManager.getProperty("webui.browse.index.1");
                if(defaultBrowseIndex != null)
                {
                    type = defaultBrowseIndex.split(":")[0];
                }
            }

            BrowseIndex bi = BrowseIndex.getBrowseIndex(type);
            if (bi == null)
            {
                throw new ResourceNotFoundException("Browse index " + type + " not found");
            }

            // If we don't have a sort column
            if (sortBy == -1)
            {
                // Get the default one
                SortOption so = bi.getSortOption();
                if (so != null)
                {
                    sortBy = so.getNumber();
                }
            }
            else if (bi.isItemIndex() && !bi.isInternalIndex())
            {
                try
                {
                    // If a default sort option is specified by the index, but it isn't
                    // the same as sort option requested, attempt to find an index that
                    // is configured to use that sort by default
                    // This is so that we can then highlight the correct option in the navigation
                    SortOption bso = bi.getSortOption();
                    SortOption so = SortOption.getSortOption(sortBy);
                    if ( bso != null && !bso.equals(so))
                    {
                        BrowseIndex newBi = BrowseIndex.getBrowseIndex(so);
                        if (newBi != null)
                        {
                            bi   = newBi;
                            type = bi.getName();
                        }
                    }
                }
                catch (SortException se)
                {
                    throw new UIException("Unable to get sort options", se);
                }
            }

            params.scope.setBrowseIndex(bi);
            params.scope.setSortBy(sortBy);

            params.scope.setJumpToItem(RequestUtils.getIntParameter(request, BrowseParams.JUMPTO_ITEM));
            params.scope.setOrder(request.getParameter(BrowseParams.ORDER));
            int offset = RequestUtils.getIntParameter(request, BrowseParams.OFFSET);
            params.scope.setOffset(offset > 0 ? offset : 0);
            params.scope.setResultsPerPage(RequestUtils.getIntParameter(request, BrowseParams.RESULTS_PER_PAGE));
            params.scope.setStartsWith(decodeFromURL(request.getParameter(BrowseParams.STARTS_WITH)));
            String filterValue = request.getParameter(BrowseParams.FILTER_VALUE[0]);
            if (filterValue == null)
            {
                filterValue = request.getParameter(BrowseParams.FILTER_VALUE[1]);
                params.scope.setAuthorityValue(filterValue);
            }

            params.scope.setFilterValue(filterValue);
            params.scope.setJumpToValue(decodeFromURL(request.getParameter(BrowseParams.JUMPTO_VALUE)));
            params.scope.setJumpToValueLang(decodeFromURL(request.getParameter(BrowseParams.JUMPTO_VALUE_LANG)));
            params.scope.setFilterValueLang(decodeFromURL(request.getParameter(BrowseParams.FILTER_VALUE_LANG)));

            // Filtering to a value implies this is a second level browse
            if (params.scope.getFilterValue() != null)
            {
                params.scope.setBrowseLevel(1);
            }

            // if year and perhaps month have been selected, we translate these
            // into "startsWith"
            // if startsWith has already been defined then it is overwritten
            if (params.year != null && !"".equals(params.year) && !"-1".equals(params.year))
            {
                String startsWith = params.year;
                if ((params.month != null) && !"-1".equals(params.month) && !"".equals(params.month))
                {
                    // subtract 1 from the month, so the match works
                    // appropriately
                    if ("ASC".equals(params.scope.getOrder()))
                    {
                        params.month = Integer.toString((Integer.parseInt(params.month) - 1));
                    }

                    // They've selected a month as well
                    if (params.month.length() == 1)
                    {
                        // Ensure double-digit month number
                        params.month = "0" + params.month;
                    }

                    startsWith = params.year + "-" + params.month;

                    if ("ASC".equals(params.scope.getOrder()))
                    {
                        startsWith = startsWith + "-32";
                    }
                }

                params.scope.setStartsWith(startsWith);
            }
        }
        catch (BrowseException bex)
        {
            throw new UIException("Unable to create browse parameters", bex);
        }

        this.userParams = params;
        return params;
    }
    @Override
    public void addBody(Body body) throws SAXException, WingException, UIException, SQLException, IOException, AuthorizeException {
        Request request = ObjectModelHelper.getRequest(objectModel);
        DSpaceObject dso = HandleUtil.obtainHandle(objectModel);
        BrowseParams params = null;

        try {
            params = getUserParams();
        } catch (ResourceNotFoundException e) {
            HttpServletResponse response = (HttpServletResponse)objectModel
                    .get(HttpEnvironment.HTTP_RESPONSE_OBJECT);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }

        BrowseInfo info = getBrowseInfo();
        SearchFilterParam browseParams = new SearchFilterParam(request);
        // Build the DRI Body
        Division div = body.addDivision("browse-by-" + request.getParameter(SearchFilterParam.FACET_FIELD), "primary");
        div.setHead(message("xmlui.Discovery.AbstractSearch.type_" + browseParams.getFacetField()));
        addBrowseControls(div, info, params);

        // Set up the major variables
        //Collection collection = (Collection) dso;
        // Build the collection viewer division.

        //Make sure we get our results
        queryResults = getQueryResponse(dso);
        if (this.queryResults != null) {

            Map<String, List<DiscoverResult.FacetResult>> facetFields = this.queryResults.getFacetResults();
            if (facetFields == null)
            {
                facetFields = new LinkedHashMap<String, List<DiscoverResult.FacetResult>>();

            }

//            facetFields.addAll(this.queryResults.getFacetDates());

            if (facetFields.size() > 0) {

                String facetField = facetFields.keySet().toArray(new String[facetFields.size()])[0];
                Map<String, String> parameters = new HashMap<String, String>();
                parameters.put("page", "{pageNum}");
                parameters.put("field", facetField);
                parameters.put("rpp",String.valueOf(getParameterRpp()));
                String pageURLMask = AbstractDSpaceTransformer.generateURL("search-filter", parameters);
                Division results = body.addDivision("browse-by-" + facetField + "-results", "primary");

                pageURLMask = addFilterQueriesToUrl(pageURLMask);
                String searchUrl = ConfigurationManager.getProperty("dspace.url") + "/JSON/discovery/search";

                results.addHidden("discovery-json-search-url").setValue(searchUrl);
                DSpaceObject currentScope = getScope();
                if(currentScope != null){
                    results.addHidden("discovery-json-scope").setValue(currentScope.getHandle());
                }
                results.addHidden("contextpath").setValue(contextPath);


                java.util.List<DiscoverResult.FacetResult> values = facetFields.get(facetField);
                Division searchBoxDivision = results.addDivision("discovery-search-box", "discoverySearchBox");

                Division mainSearchDiv = searchBoxDivision.addInteractiveDivision("general-query",
                        "discover", Division.METHOD_GET, "discover-search-box");


                addHiddenFormFields("search", request, DiscoveryUIUtils.getParameterFilterQueries(ObjectModelHelper.getRequest(objectModel)), mainSearchDiv);

                buildSearchControls(results);


                if (values != null && 0 < values.size()) {



                    // Find our faceting offset
                    int offSet = queryArgs.getFacetOffset();
                    if(offSet == -1){
                        offSet = 0;
                    }

                    //Only show the nextpageurl if we have at least one result following our current results
                    String nextPageUrl = null;
                    if (values.size() == (DEFAULT_PAGE_SIZE + 1))
                    {
                        nextPageUrl = getNextPageURL(browseParams, request);
                    }



                    int shownItemsMax = offSet + (DEFAULT_PAGE_SIZE < values.size() ? values.size() - 1 : values.size());

                    int itemsTotal = (int) queryResults.getTotalSearchResults();
                    int firstItemIndex = (int) this.queryResults.getStart() + 1;
                    int lastItemIndex = (int) this.queryResults.getStart() + queryResults.getDspaceObjects().size();

                    //if (itemsTotal < lastItemIndex)
                    //    lastItemIndex = itemsTotal;
                    int currentPage = this.queryResults.getStart() / (this.queryResults.getMaxResults() + 1);
                    int pagesTotal = (int) ((this.queryResults.getTotalSearchResults() - 1) / this.queryResults.getMaxResults()) + 1;

                    // We put our total results to -1 so this doesn't get shown in the results (will be hidden by the xsl)
                    // The reason why we do this is because solr 1.4 can't retrieve the total number of facets found
                    /*results.setMaskedPagination((int) queryResults.getTotalSearchResults(), offSet + 1,
                            shownItemsMax, getPreviousPageURL(browseParams, request), nextPageUrl);*/
                   /* results.setMaskedPagination(itemsTotal, firstItemIndex,
                            lastItemIndex, currentPage, pagesTotal, pageURLMask);*/
                    results.setSimplePagination((int) queryResults.getTotalSearchResults(), offSet + 1,
                            shownItemsMax, getPreviousPageURL(browseParams, request), nextPageUrl);

                    Table singleTable = results.addTable("browse-by-" + facetField + "-results", (int) (queryResults.getDspaceObjects().size() + 1), 1);

                    List<String> filterQueries = Arrays.asList(DiscoveryUIUtils.getFilterQueries(request, context));


                    int end = values.size();
                    if(DEFAULT_PAGE_SIZE < end)
                    {
                        end = DEFAULT_PAGE_SIZE;
                    }

                    for (int i = 0; i < end; i++) {
                        DiscoverResult.FacetResult value = values.get(i);
                        renderFacetField(browseParams, dso, facetField, singleTable, filterQueries, value);
                    }
                }else{
                    results.addPara(message("xmlui.discovery.SearchFacetFilter.no-results"));
                }
            }
        }
    }



    private void renderFacetField(SearchFilterParam browseParams, DSpaceObject dso, String facetField, Table singleTable, List<String> filterQueries, DiscoverResult.FacetResult value) throws SQLException, WingException, UnsupportedEncodingException {
        String displayedValue = value.getDisplayedValue();
//        if(field.getGap() != null){
//            //We have a date get the year so we can display it
//            DateFormat simpleDateformat = new SimpleDateFormat("yyyy");
//            displayedValue = simpleDateformat.format(SolrServiceImpl.toDate(displayedValue));
//            filterQuery = ClientUtils.escapeQueryChars(value.getFacetField().getName()) + ":" + displayedValue + "*";
//        }

        Cell cell = singleTable.addRow().addCell();

        //No use in selecting the same filter twice
        if(filterQueries.contains(searchService.toFilterQuery(context,  facetField, value.getFilterType(), value.getAsFilterQuery()).getFilterQuery())){
            cell.addContent(displayedValue + " (" + value.getCount() + ")");
        } else {
            //Add the basics
            Map<String, String> urlParams = new HashMap<String, String>();
            urlParams.putAll(browseParams.getCommonBrowseParams());
            String url = generateURL(contextPath + (dso == null ? "" : "/handle/" + dso.getHandle()) + "/discover", urlParams);
            //Add already existing filter queries
            url = addFilterQueriesToUrl(url);
            //Last add the current filter query
            url += "&filtertype=" + facetField;
            url += "&filter_relational_operator="+value.getFilterType();
            url += "&filter=" + URLEncoder.encode(value.getAsFilterQuery(), "UTF-8");
            cell.addXref(url, displayedValue + " (" + value.getCount() + ")"
            );
        }
    }

    private String getNextPageURL(SearchFilterParam browseParams, Request request) throws UnsupportedEncodingException, UIException {
        int offSet = Util.getIntParameter(request, SearchFilterParam.OFFSET);
        if (offSet == -1)
        {
            offSet = 0;
        }

        Map<String, String> parameters = new HashMap<String, String>();
        parameters.putAll(browseParams.getCommonBrowseParams());
        parameters.putAll(browseParams.getControlParameters());
        parameters.put(SearchFilterParam.OFFSET, String.valueOf(offSet + DEFAULT_PAGE_SIZE));

        // Add the filter queries
        String url = generateURL("search-filter", parameters);
        url = addFilterQueriesToUrl(url);

        return url;
    }

    private String getPreviousPageURL(SearchFilterParam browseParams, Request request) throws UnsupportedEncodingException, UIException {
        //If our offset should be 0 then we shouldn't be able to view a previous page url
        if (0 == queryArgs.getFacetOffset() && Util.getIntParameter(request, "offset") == -1)
        {
            return null;
        }

        int offset = Util.getIntParameter(request, SearchFilterParam.OFFSET);
        if(offset == -1 || offset == 0)
        {
            return null;
        }

        Map<String, String> parameters = new HashMap<String, String>();
        parameters.putAll(browseParams.getCommonBrowseParams());
        parameters.putAll(browseParams.getControlParameters());
        parameters.put(SearchFilterParam.OFFSET, String.valueOf(offset - DEFAULT_PAGE_SIZE));

        // Add the filter queries
        String url = generateURL("search-filter", parameters);
        url = addFilterQueriesToUrl(url);
        return url;
    }


    /**
     * Recycle
     */
    public void recycle() {
        // Clear out our item's cache.
        this.queryResults = null;
        this.validity = null;
        super.recycle();
    }

    public String addFilterQueriesToUrl(String url) throws UIException {
        Map<String, String[]> fqs = DiscoveryUIUtils.getParameterFilterQueries(ObjectModelHelper.getRequest(objectModel));
        if (fqs != null)
        {
            StringBuilder urlBuilder = new StringBuilder(url);
            for (String parameter : fqs.keySet())
            {
                for (int i = 0; i < fqs.get(parameter).length; i++)
                {
                    String value = fqs.get(parameter)[i];
                    urlBuilder.append("&").append(parameter).append("=").append(encodeForURL(value));
                }
            }
            return urlBuilder.toString();
        }

        return url;
    }

    private static class SearchFilterParam {
        private Request request;

        /** The always present commond params **/
        public static final String QUERY = "query";
        public static final String FACET_FIELD = "field";

        /** The browse control params **/
        public static final String OFFSET = "offset";
        public static final String STARTS_WITH = "starts_with";


        private SearchFilterParam(Request request){
            this.request = request;
        }

        public String getFacetField(){
            return request.getParameter(FACET_FIELD);
        }

        public Map<String, String> getCommonBrowseParams(){
            Map<String, String> result = new HashMap<String, String>();
            result.put(FACET_FIELD, request.getParameter(FACET_FIELD));
            if(request.getParameter(QUERY) != null)
                result.put(QUERY, request.getParameter(QUERY));
            if(request.getParameter("scope") != null){
                result.put("scope", request.getParameter("scope"));
            }
            return result;
        }

        public Map<String, String> getControlParameters(){
            Map<String, String> paramMap = new HashMap<String, String>();

            paramMap.put(OFFSET, request.getParameter(OFFSET));
            if(request.getParameter(STARTS_WITH) != null)
            {
                paramMap.put(STARTS_WITH, request.getParameter(STARTS_WITH));
            }

            return paramMap;
        }
    }

    /**
     * Determine the current scope. This may be derived from the current url
     * handle if present or the scope parameter is given. If no scope is
     * specified then null is returned.
     *
     * @return The current scope.
     */
    private DSpaceObject getScope() throws SQLException {
        Request request = ObjectModelHelper.getRequest(objectModel);
        String scopeString = request.getParameter("scope");

        // Are we in a community or collection?
        DSpaceObject dso;
        if (scopeString == null || "".equals(scopeString))
        {
            // get the search scope from the url handle
            dso = HandleUtil.obtainHandle(objectModel);
        }
        else
        {
            // Get the search scope from the location parameter
            dso = HandleManager.resolveToObject(context, scopeString);
        }

        return dso;
    }

    protected void buildSearchControls(Division div)
            throws WingException, SQLException {


        DSpaceObject dso = HandleUtil.obtainHandle(objectModel);
        DiscoveryConfiguration discoveryConfiguration = SearchUtils.getDiscoveryConfiguration(dso);

        Division searchControlsGear = div.addDivision("masked-page-control").addDivision("search-controls-gear", "controls-gear-wrapper");


        /**
         * Add sort by options, the gear will be rendered by a combination of javascript & css
         */
        org.dspace.app.xmlui.wing.element.List sortList = searchControlsGear.addList("sort-options", org.dspace.app.xmlui.wing.element.List.TYPE_SIMPLE, "gear-selection");


        //Add the rows per page
        sortList.addItem("rpp-head", "gear-head").addContent(T_rpp);
        org.dspace.app.xmlui.wing.element.List rppOptions = sortList.addList("rpp-selections");
        for (int i : RESULTS_PER_PAGE_PROGRESSION)
        {
            rppOptions.addItem("rpp-" + i, "gear-option" + (i == getParameterRpp() ? " gear-option-selected" : "")).addXref("&rpp=" + i, Integer.toString(i));
        }
    }
    protected int getParameterRpp() {
        try {
            return Integer.parseInt(ObjectModelHelper.getRequest(objectModel).getParameter("rpp"));
        }
        catch (Exception e) {
            return 10;
        }
    }
    /**
     * Since the layout is creating separate forms for each search part
     * this method will add hidden fields containing the values from other form parts
     *
     * @param type the type of our form
     * @param request the request
     * @param fqs the filter queries
     * @param division the division that requires the hidden fields
     * @throws WingException will never occur
     */
    private void addHiddenFormFields(String type, Request request, Map<String, String[]> fqs, Division division) throws WingException {
        if(type.equals("filter") || type.equals("sort")){
            if(request.getParameter("query") != null){
                division.addHidden("query").setValue(request.getParameter("query"));
            }
            if(request.getParameter("scope") != null){
                division.addHidden("scope").setValue(request.getParameter("scope"));
            }
        }

        //Add the filter queries, current search settings so these remain saved when performing a new search !
        if(type.equals("search") || type.equals("sort"))
        {
            for (String parameter : fqs.keySet())
            {
                String[] values = fqs.get(parameter);
                for (String value : values) {
                    division.addHidden(parameter).setValue(value);
                }
            }
        }

        if(type.equals("search") || type.equals("filter")){
            if(request.getParameter("rpp") != null){
                division.addHidden("rpp").setValue(request.getParameter("rpp"));
            }
            if(request.getParameter("sort_by") != null){
                division.addHidden("sort_by").setValue(request.getParameter("sort_by"));
            }
            if(request.getParameter("order") != null){
                division.addHidden("order").setValue(request.getParameter("order"));
            }
        }
    }

    /**
     * Add the controls to changing sorting and display options.
     *
     * @param div
     * @param info
     * @param params
     * @throws WingException
     */
    private void addBrowseControls(Division div, BrowseInfo info, BrowseParams params)
            throws WingException
    {
        // Prepare a Map of query parameters required for all links
        Map<String, String> queryParams = new HashMap<String, String>();

        queryParams.putAll(params.getCommonParameters());

        Division controls = div.addInteractiveDivision("browse-controls", "search-filter",
                Division.METHOD_POST, "browse controls");

        // Add all the query parameters as hidden fields on the form
        for (Map.Entry<String, String> param : queryParams.entrySet())
        {
            controls.addHidden(param.getKey()).setValue(param.getValue());
        }

        Para controlsForm = controls.addPara();



        // Create a control for the number of records to display
        controlsForm.addContent(T_rpp);
        Select rppSelect = controlsForm.addSelect(BrowseParams.RESULTS_PER_PAGE);

        for (int i : RESULTS_PER_PAGE_PROGRESSION)
        {
            rppSelect.addOption((i == info.getResultsPerPage()), i, Integer.toString(i));

        }

        // Create a control for the number of authors per item to display
        // FIXME This is currently disabled, as the supporting functionality
        // is not currently present in xmlui
        //if (isItemBrowse(info))
        //{
        //    controlsForm.addContent(T_etal);
        //    Select etalSelect = controlsForm.addSelect(BrowseParams.ETAL);
        //
        //    etalSelect.addOption((info.getEtAl() < 0), 0, T_etal_all);
        //    etalSelect.addOption(1 == info.getEtAl(), 1, Integer.toString(1));
        //
        //    for (int i = 5; i <= 50; i += 5)
        //    {
        //        etalSelect.addOption(i == info.getEtAl(), i, Integer.toString(i));
        //    }
        //}

        controlsForm.addButton("update").setValue("update");
    }
    private BrowseInfo getBrowseInfo() throws SQLException, UIException
    {
        if (this.browseInfo != null)
        {
            return this.browseInfo;
        }

        Context context = ContextUtil.obtainContext(objectModel);

        // Get the parameters we will use for the browse
        // (this includes a browse scope)
        BrowseParams params = null;
        try {
            params = getUserParams();
        } catch (ResourceNotFoundException e) {
            return null;
        }

        try
        {
            // Create a new browse engine, and perform the browse
            BrowseEngine be = new BrowseEngine(context);
            this.browseInfo = be.browse(params.scope);

            // figure out the setting for author list truncation
            if (params.etAl < 0)
            {
                // there is no limit, or the UI says to use the default
                int etAl = ConfigurationManager.getIntProperty("webui.browse.author-limit");
                if (etAl != 0)
                {
                    this.browseInfo.setEtAl(etAl);
                }

            }
            else if (params.etAl == 0) // 0 is the user setting for unlimited
            {
                this.browseInfo.setEtAl(-1); // but -1 is the application
                // setting for unlimited
            }
            else
            // if the user has set a limit
            {
                this.browseInfo.setEtAl(params.etAl);
            }
        }
        catch (BrowseException bex)
        {
            throw new UIException("Unable to process browse", bex);
        }

        return this.browseInfo;
    }


}


class BrowseParams
{
    String month;

    String year;

    int etAl;

    BrowserScope scope;

    static final String MONTH = "month";

    static final String YEAR = "year";

    static final String ETAL = "etal";

    static final String TYPE = "type";

    static final String JUMPTO_ITEM = "focus";

    static final String JUMPTO_VALUE = "vfocus";

    static final String JUMPTO_VALUE_LANG = "vfocus_lang";

    static final String ORDER = "order";

    static final String OFFSET = "offset";

    static final String RESULTS_PER_PAGE = "rpp";

    static final String SORT_BY = "sort_by";

    static final String STARTS_WITH = "starts_with";

    static final String[] FILTER_VALUE = new String[]{"value","authority"};

    static final String FILTER_VALUE_LANG = "value_lang";

    /*
     * Creates a map of the browse options common to all pages (type / value /
     * value language)
     */
    Map<String, String> getCommonParameters() throws UIException
    {
        Map<String, String> paramMap = new HashMap<String, String>();

        paramMap.put(BrowseParams.TYPE, scope.getBrowseIndex().getName());

        if (scope.getFilterValue() != null)
        {
            paramMap.put(scope.getAuthorityValue() != null?
                    BrowseParams.FILTER_VALUE[1]:BrowseParams.FILTER_VALUE[0], scope.getFilterValue());
        }

        if (scope.getFilterValueLang() != null)
        {
            paramMap.put(BrowseParams.FILTER_VALUE_LANG, scope.getFilterValueLang());
        }

        return paramMap;
    }

    Map<String, String> getCommonParametersEncoded() throws UIException
    {
        Map<String, String> paramMap = getCommonParameters();
        Map<String, String> encodedParamMap = new HashMap<String, String>();

        for (Map.Entry<String, String> param : paramMap.entrySet())
        {
            encodedParamMap.put(param.getKey(), AbstractDSpaceTransformer.encodeForURL(param.getValue()));
        }

        return encodedParamMap;
    }


    /*
     * Creates a Map of the browse control options (sort by / ordering / results
     * per page / authors per item)
     */
    Map<String, String> getControlParameters() throws UIException
    {
        Map<String, String> paramMap = new HashMap<String, String>();

        paramMap.put(BrowseParams.SORT_BY, Integer.toString(this.scope.getSortBy()));
        paramMap
                .put(BrowseParams.ORDER, AbstractDSpaceTransformer.encodeForURL(this.scope.getOrder()));
        paramMap.put(BrowseParams.RESULTS_PER_PAGE, Integer
                .toString(this.scope.getResultsPerPage()));
        paramMap.put(BrowseParams.ETAL, Integer.toString(this.etAl));

        return paramMap;
    }

    String getKey()
    {
        try
        {
            String key = "";

            key += "-" + scope.getBrowseIndex().getName();
            key += "-" + scope.getBrowseLevel();
            key += "-" + scope.getStartsWith();
            key += "-" + scope.getOrder();
            key += "-" + scope.getResultsPerPage();
            key += "-" + scope.getSortBy();
            key += "-" + scope.getSortOption().getNumber();
            key += "-" + scope.getOffset();
            key += "-" + scope.getJumpToItem();
            key += "-" + scope.getFilterValue();
            key += "-" + scope.getFilterValueLang();
            key += "-" + scope.getJumpToValue();
            key += "-" + scope.getJumpToValueLang();
            key += "-" + etAl;

            return key;
        }
        catch (RuntimeException re)
        {
            throw re;
        }
        catch (Exception e)
        {
            return null; // ignore exception and return no key
        }
    }
}

