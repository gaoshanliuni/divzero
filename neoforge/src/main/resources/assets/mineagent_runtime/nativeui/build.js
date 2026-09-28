// Trusted DivZero program, executed in a dedicated KubeJS client context.
// AI definitions are parsed as data; they are never evaluated as JavaScript.
(function () {
    const definition = JSON.parse(definitionJson);
    function build(node) {
        const element = bridge.create(JSON.stringify(node));
        for (const child of (node.children || [])) {
            bridge.add(element, build(child));
        }
        if (node.type === 'input' || node.type === 'toggle') {
            bridge.listen(element, 'change', value => bridge.dispatch(node.id, 'change', value));
        }
        if (node.events && node.events.click) {
            bridge.listen(element, 'click', value => bridge.dispatch(node.id, 'click', value));
        }
        return element;
    }
    bridge.finish(build(definition.root));
})();
