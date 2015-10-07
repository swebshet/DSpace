package org.dspace.app.xmlui.objectmanager;

import org.apache.cocoon.environment.Request;
import org.dspace.content.Item;
import org.dspace.content.Metadatum;
import org.dspace.core.Context;

import java.util.Collection;

/**
 * Created by Roeland Dillen (roeland at atmire dot com)
 * Date: 01/10/12
 * Time: 10:46
 */
public interface ItemAdapterMetadataPlugin {
	Collection<? extends Metadatum> generateMetadataForItem(Request request, Context context, Item item);
}
