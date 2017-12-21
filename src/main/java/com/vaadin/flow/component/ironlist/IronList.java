/*
 * Copyright 2000-2017 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.component.ironlist;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.vaadin.flow.component.ClientDelegate;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.HtmlImport;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.data.binder.HasDataProvider;
import com.vaadin.flow.data.provider.ArrayUpdater;
import com.vaadin.flow.data.provider.ArrayUpdater.Update;
import com.vaadin.flow.data.provider.ComponentDataGenerator;
import com.vaadin.flow.data.provider.CompositeDataGenerator;
import com.vaadin.flow.data.provider.DataCommunicator;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.ValueProvider;
import com.vaadin.flow.internal.JsonSerializer;
import com.vaadin.flow.internal.JsonUtils;
import com.vaadin.flow.renderer.ComponentRenderer;
import com.vaadin.flow.renderer.ComponentTemplateRenderer;
import com.vaadin.flow.renderer.TemplateRenderer;
import com.vaadin.flow.renderer.TemplateRendererUtil;
import com.vaadin.flow.shared.Registration;

import elemental.json.JsonValue;

/**
 * Component that encapsulates the functionality of the {@code <iron-list>}
 * webcomponent.
 * <p>
 * It supports {@link DataProvider}s to load data asynchronously and
 * {@link TemplateRenderer}s to render the markup for each item.
 * <p>
 * For this component to work properly, it needs to have a well defined
 * {@code height}. It can be an absolute height, like {@code 100px}, or a
 * relative height inside a container with well defined height.
 * <p>
 * For list renderered in grid layout (setting {@link #setGridLayout(boolean)}
 * with <code>true</code>), the {@code width} of the component also needs to be
 * well defined.
 *
 *
 * @author Vaadin Ltd.
 *
 * @param <T>
 *            the type of the items supported by the list
 * @see <a href=
 *      "https://www.webcomponents.org/element/PolymerElements/iron-list">iron-list
 *      webcomponent documentation</a>
 */
@Tag("iron-list")
@HtmlImport("frontend://bower_components/iron-list/iron-list.html")
@HtmlImport("frontend://flow-component-renderer.html")
@JavaScript("frontend://ironListConnector.js")
public class IronList<T> extends Component implements HasDataProvider<T>,
        HasStyle, HasSize, Focusable<IronList<T>> {

    private final class UpdateQueue implements Update {
        private List<Runnable> queue = new ArrayList<>();

        private UpdateQueue(int size) {
            enqueue("$connector.updateSize", size);
        }

        @Override
        public void set(int start, List<JsonValue> items) {
            enqueue("$connector.set", start,
                    items.stream().collect(JsonUtils.asArray()));
        }

        @Override
        public void clear(int start, int length) {
            enqueue("$connector.clear", start, length);
        }

        @Override
        public void commit(int updateId) {
            getDataCommunicator().confirmUpdate(updateId);
            queue.forEach(Runnable::run);
            queue.clear();
        }

        private void enqueue(String name, Serializable... arguments) {
            queue.add(() -> getElement().callFunction(name, arguments));
        }
    }

    private final ArrayUpdater arrayUpdater = UpdateQueue::new;
    private final Element template;
    private TemplateRenderer<T> renderer;
    private String placeholderTemplate;

    private final CompositeDataGenerator<T> dataGenerator = new CompositeDataGenerator<>();
    private Registration dataGeneratorRegistration;

    private final DataCommunicator<T> dataCommunicator = new DataCommunicator<>(
            dataGenerator, arrayUpdater,
            data -> getElement().callFunction("$connector.updateData", data),
            getElement().getNode());

    /**
     * Creates an empty list.
     */
    public IronList() {
        dataGenerator.addDataGenerator(
                (item, jsonObject) -> renderer.getValueProviders()
                        .forEach((property, provider) -> jsonObject.put(
                                property,
                                JsonSerializer.toJson(provider.apply(item)))));

        template = new Element("template");
        getElement().appendChild(template);
        placeholderTemplate = "<div style='width: 100px; height: 18px'></div>";
        setRenderer(item -> String.valueOf(item));

        getElement().getNode()
                .runWhenAttached(ui -> ui.beforeClientResponse(this,
                        () -> ui.getPage().executeJavaScript(
                                "window.ironListConnector.initLazy($0)",
                                getElement())));
    }

    @Override
    public void setDataProvider(DataProvider<T, ?> dataProvider) {
        Objects.requireNonNull(dataProvider, "The dataProvider cannot be null");
        getDataCommunicator().setDataProvider(dataProvider, null);
    }

    /**
     * Returns the data provider of this list.
     *
     * @return the data provider of this list, not {@code null}
     */
    public DataProvider<T, ?> getDataProvider() {
        return getDataCommunicator().getDataProvider();
    }

    /**
     * Returns the data communicator of this list.
     *
     * @return the data communicator, not {@code null}
     */
    public DataCommunicator<T> getDataCommunicator() {
        return dataCommunicator;
    }

    /**
     * Sets a renderer for the items in the list, by using a
     * {@link ValueProvider}. The String returned by the provider is used to
     * render each item.
     *
     * @param valueProvider
     *            a provider for the label string for each item in the list, not
     *            <code>null</code>
     */
    public void setRenderer(ValueProvider<T, String> valueProvider) {
        Objects.requireNonNull(valueProvider,
                "The valueProvider must not be null");
        this.setRenderer(TemplateRenderer.<T> of("[[item.label]]")
                .withProperty("label", valueProvider));
    }

    /**
     * Sets a renderer for the items in the list, by using a
     * {@link TemplateRenderer}. The template returned by the renderer is used
     * to render each item.
     * <p>
     * Note: {@link ComponentRenderer}s are not supported yet.
     *
     * @param renderer
     *            a renderer for the items in the list, not <code>null</code>
     */
    public void setRenderer(TemplateRenderer<T> renderer) {
        Objects.requireNonNull(renderer, "The renderer must not be null");

        if (dataGeneratorRegistration != null) {
            dataGeneratorRegistration.remove();
            dataGeneratorRegistration = null;
        }

        if (renderer instanceof ComponentTemplateRenderer) {
            ComponentTemplateRenderer<? extends Component, T> componentRenderer = (ComponentTemplateRenderer<? extends Component, T>) renderer;
            dataGeneratorRegistration = setupItemComponentRenderer(this,
                    componentRenderer);
        }
        this.renderer = renderer;
        updateTemplateInnerHtml();

        TemplateRendererUtil.registerEventHandlers(renderer, template,
                this.getElement(),
                key -> getDataCommunicator().getKeyMapper().get(key));

        getDataCommunicator().reset();
    }

    /**
     * Sets a HTML template for the placeholder item. The placeholder is shown
     * in the list while the actual data is being fetched from the server.
     * <p>
     * For a smooth scrolling experience, it is recommended that the placeholder
     * has the dimensions as close as possible to the final, rendered result.
     * This is specially important when using {@link TemplateRenderer} or
     * {@link ComponentTemplateRenderer}.
     * <p>
     * By default, the placeholder is an empty {@code <div>}, with {@code 100px}
     * of width and {@code 18px} of height.
     * 
     * @param placeholderTemplate
     *            the HTML to be used in the list while the actual item is being
     *            loaded, not <code>null</code>
     */
    public void setPlaceholderTemplate(String placeholderTemplate) {
        Objects.requireNonNull(placeholderTemplate,
                "The placeholderTemplate must not be null");

        this.placeholderTemplate = placeholderTemplate;
        updateTemplateInnerHtml();
    }

    private void updateTemplateInnerHtml() {
        /**
         * The placeholder is used by the client connector to create temporary
         * elements that are populated on demand (when the user scrolls to that
         * item).
         */
        template.setProperty("innerHTML", String.format(
        //@formatter:off
            "<span>"
                + "<template is='dom-if' if='[[item.__placeholder]]'>%s</template>"
                + "<template is='dom-if' if='[[!item.__placeholder]]'>%s</template>"
            + "</span>",
        //@formatter:on
                placeholderTemplate, renderer.getTemplate()));
    }

    /**
     * Gets whether this list is rendered in a grid layout instead of a linear
     * list.
     *
     * @return <code>true</code> if the list renders itself as a grid,
     *         <code>false</code> otherwise
     */
    public boolean isGridLayout() {
        return getElement().getProperty("grid", false);
    }

    /**
     * Sets this list to be rendered as a grid. Note that for the grid layout to
     * work properly, the component needs to have a well defined {@code width}
     * and {@code height}.
     *
     * @param gridLayout
     *            <code>true</code> to make the list renders itself as a grid,
     *            <code>false</code> to make it render as a linear list
     */
    public void setGridLayout(boolean gridLayout) {
        getElement().setProperty("grid", gridLayout);
    }

    @ClientDelegate
    private void setRequestedRange(int start, int length) {
        getDataCommunicator().setRequestedRange(start, length);
    }

    private Registration setupItemComponentRenderer(Component owner,
            ComponentTemplateRenderer<? extends Component, T> componentRenderer) {

        Element container = new Element("div", false);
        owner.getElement().appendVirtualChild(container);

        String appId = UI.getCurrent().getInternals().getAppId();

        componentRenderer.setTemplateAttribute("appid", appId);
        componentRenderer.setTemplateAttribute("nodeid", "[[item.nodeId]]");

        return dataGenerator.addDataGenerator(
                new ComponentDataGenerator<>(componentRenderer, container,
                        "nodeId", getDataCommunicator().getKeyMapper()));
    }
}
