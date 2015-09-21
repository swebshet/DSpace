(function ($) {
    window.atmire = window.atmire || {};
    window.atmire.CUA = window.atmire.CUA || {};
    window.atmire.CUA.statlet = window.atmire.CUA.statlet || {};
    var templateMapper = {bar: "bar", line: "bar", donut: "pie", pie: "pie"};

    function override() {
        if (typeof window.atmire.CUA.statlet.template === 'undefined') {
            setTimeout(override, 10);
            return;
        }

        // overriding a function originally defined in aspects/ReportingSuite/statlet/charts/chartist.js
        var statletNS = atmire.CUA.statlet;
        statletNS.template = statletNS.template || {};
        statletNS.template.graph = function (statlet) {
            var templateName, templateFunction;
            var type = (statlet.render.default && statlet.render.default['graph-type']) || "bar";
            if (type === "bar") {
                templateName = 'customized_' + templateMapper[type];
                templateFunction = DSpace.templates[templateName];
            } else {
                templateName = 'statlet/graphs/chartist/' + templateMapper[type];
                templateFunction = atmire.CUA.getTemplate(templateName);
            }
            return templateFunction(statlet);
        };

    }

    override();


})(jQuery);

