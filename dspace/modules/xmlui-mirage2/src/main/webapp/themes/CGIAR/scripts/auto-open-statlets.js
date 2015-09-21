(function($) {
    window.atmire = window.atmire || {};
    window.atmire.CUA = window.atmire.CUA || {};
    window.atmire.CUA.statlet = window.atmire.CUA.statlet || {};
    var afterInitCallbacks = window.atmire.CUA.statlet.afterInitCallbacks = window.atmire.CUA.statlet.afterInitCallbacks || [];
    $(function() {
        afterInitCallbacks.push(function() {
            var statsWrapper = $('#aspect_statistics_StatletTransformer_div_statswrapper');
            if (statsWrapper.length === 0 || statsWrapper.is(':hidden')) {
                $('#aspect_statistics_StatletTransformer_div_showStats').find('.btn').click();
            }
        });
    });
})(jQuery);