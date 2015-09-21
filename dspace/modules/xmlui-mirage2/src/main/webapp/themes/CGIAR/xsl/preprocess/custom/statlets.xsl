<?xml version="1.0" encoding="UTF-8"?>


<xsl:stylesheet
        xmlns="http://di.tamu.edu/DRI/1.0/"
        xmlns:dri="http://di.tamu.edu/DRI/1.0/"
        xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0"
        xmlns:i18n="http://apache.org/cocoon/i18n/2.1"
        exclude-result-prefixes="xsl dri i18n">

    <xsl:output indent="yes"/>

    <xsl:template match="dri:body[dri:div[@id='aspect.artifactbrowser.ItemViewer.div.item-view']][dri:div[@id='aspect.statistics.StatletTransformer.div.showStats']]">
        <body>
            <xsl:call-template name="copy-attributes"/>
            <xsl:apply-templates select="dri:div[@id='aspect.artifactbrowser.ItemViewer.div.item-view']"/>
            <xsl:apply-templates select="dri:div[@id='aspect.statistics.StatletTransformer.div.showStats']"/>
            <xsl:apply-templates select="*[not(@id='aspect.artifactbrowser.ItemViewer.div.item-view')][not(@id='aspect.statistics.StatletTransformer.div.showStats')]"/>
        </body>
    </xsl:template>

</xsl:stylesheet>
