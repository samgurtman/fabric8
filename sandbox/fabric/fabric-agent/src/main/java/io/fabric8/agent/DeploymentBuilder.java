/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.agent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import aQute.bnd.osgi.Macro;
import aQute.bnd.osgi.Processor;
import io.fabric8.agent.download.DownloadManager;
import io.fabric8.agent.repository.AggregateRepository;
import io.fabric8.agent.repository.StaticRepository;
import io.fabric8.agent.resolver.FeatureNamespace;
import io.fabric8.agent.resolver.FeatureResource;
import io.fabric8.agent.resolver.RequirementImpl;
import io.fabric8.agent.resolver.ResolveContextImpl;
import io.fabric8.agent.resolver.ResourceBuilder;
import io.fabric8.agent.resolver.ResourceImpl;
import io.fabric8.agent.resolver.Slf4jResolverLog;
import io.fabric8.agent.utils.AgentUtils;
import io.fabric8.utils.Manifests;
import io.fabric8.utils.MultiException;
import io.fabric8.utils.Strings;
import io.fabric8.fab.DependencyTree;
import io.fabric8.fab.osgi.FabBundleInfo;
import io.fabric8.fab.osgi.FabResolver;
import io.fabric8.fab.osgi.FabResolverFactory;
import org.apache.felix.resolver.ResolverImpl;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Conditional;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.Repository;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.agent.resolver.UriNamespace.getUri;
import static io.fabric8.agent.utils.AgentUtils.FAB_PROTOCOL;
import static io.fabric8.agent.utils.AgentUtils.REQ_PROTOCOL;
import static io.fabric8.utils.PatchUtils.extractUrl;
import static io.fabric8.utils.PatchUtils.extractVersionRange;
import static org.apache.felix.resolver.Util.getSymbolicName;
import static org.apache.felix.resolver.Util.getVersion;
import static org.osgi.framework.namespace.IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE;
import static org.osgi.framework.namespace.IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE;
import static org.osgi.framework.namespace.IdentityNamespace.IDENTITY_NAMESPACE;
import static org.osgi.resource.Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE;
import static org.osgi.resource.Namespace.RESOLUTION_MANDATORY;
import static org.osgi.resource.Namespace.RESOLUTION_OPTIONAL;

/**
 */
public class DeploymentBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentBuilder.class);

    private static final String[] PROTOCOLS = { "blueprint", "spring", "profile", "wrap", "war" };

    private final DownloadManager manager;
    private final FabResolverFactory fabResolverFactory;
    private final Collection<Repository> repositories;

    private final List<org.osgi.service.repository.Repository> resourceRepos;

    String featureRange = "${version;==}";

    AgentUtils.FileDownloader downloader;
    ResourceImpl requirements;
    Map<String, Resource> resources;
    Map<String, StreamProvider> providers;
    long urlHandlersTimeout;
    Map<Resource, List<Wire>> wiring;

    Set<Feature> featuresToRegister = new HashSet<Feature>();

    Map<String, Map<VersionRange, Map<String, String>>> metadata;

    public interface DeploymentDownloadListener {
        void onDownload(File file, int pending);
    }

    public DeploymentBuilder(DownloadManager manager,
                             FabResolverFactory fabResolverFactory,
                             Collection<Repository> repositories,
                             long urlHandlersTimeout) {
        this.manager = manager;
        this.fabResolverFactory = fabResolverFactory;
        this.repositories = repositories;
        this.resourceRepos = new ArrayList<org.osgi.service.repository.Repository>();
        this.urlHandlersTimeout = urlHandlersTimeout;
    }

    public void addResourceRepository(org.osgi.service.repository.Repository repository) {
        resourceRepos.add(repository);
    }

    public Map<String, StreamProvider> getProviders() {
        return providers;
    }

    public Map<String, Resource> download(Set<String> features,
                         Set<String> bundles,
                         Set<String> fabs,
                         Set<String> reqs,
                         Set<String> overrides,
                         Set<String> optionals,
                         Map<String, Map<VersionRange, Map<String, String>>> metadata,
                         DeploymentDownloadListener listener) throws IOException, MultiException, InterruptedException, ResolutionException {
        this.downloader = new AgentUtils.FileDownloader(manager);
        this.resources = new ConcurrentHashMap<String, Resource>();
        this.providers = new ConcurrentHashMap<String, StreamProvider>();
        this.requirements = new ResourceImpl("dummy", "dummy", Version.emptyVersion);
        this.metadata = metadata;
        // First, gather all bundle resources
        for (String feature : features) {
            registerMatchingFeatures(feature);
        }
        for (String bundle : bundles) {
            downloadAndBuildResource(bundle, listener);
        }
        for (String fab : fabs) {
            downloadAndBuildResource(FAB_PROTOCOL + fab, listener);
        }
        for (String req : reqs) {
            downloadAndBuildResource(REQ_PROTOCOL + req, listener);
        }
        for (String override : overrides) {
            // TODO: ignore download failures for overrides
            downloadAndBuildResource(extractUrl(override), listener);
        }
        for (String optional : optionals) {
            downloadAndBuildResource(optional, listener);
        }
        // Wait for all resources to be created
        downloader.await();
        // Do override replacement
        for (String override : overrides) {
            Resource over = resources.remove(extractUrl(override));
            if (over == null) {
                // Ignore invalid overrides
                continue;
            }
            for (String uri : new ArrayList<String>(resources.keySet())) {
                Resource res = resources.get(uri);
                if (getSymbolicName(res).equals(getSymbolicName(over))) {
                    VersionRange range;
                    String vr = extractVersionRange(override);
                    if (vr == null) {
                        // default to micro version compatibility
                        Version v1 = getVersion(res);
                        Version v2 = new Version(v1.getMajor(), v1.getMinor() + 1, 0);
                        range = new VersionRange(false, v1, v2, true);
                    } else {
                        range = VersionRange.parseVersionRange(vr);
                    }
                    // The resource matches, so replace it with the overridden resource
                    // if the override is actually a newer version than what we currently have
                    if (range.contains(getVersion(res)) && getVersion(res).compareTo(getVersion(over)) < 0) {
                        resources.put(uri, over);
                    }
                }
            }
        }
        // Build features resources
        for (Feature feature : featuresToRegister) {
            ResourceImpl resource = FeatureResource.build(feature, featureRange, resources);
            resources.put("feature:" + feature.getName() + "/" + feature.getVersion(), resource);
            for (Conditional cond : feature.getConditional()) {
                Feature featCond = cond.asFeature(feature.getName(), feature.getVersion());
                FeatureResource resCond = FeatureResource.build(feature, cond, featureRange, resources);
                requireFeature(featCond.getName() + "/" + featCond.getVersion(), resource, true);
                resources.put("feature:" + featCond.getName() + "/" + featCond.getVersion(), resCond);
            }
        }
        // Build requirements
        for (String feature : features) {
            requireFeature(feature, requirements);
        }
        for (String bundle : bundles) {
            requireResource(bundle);
        }
        for (String req : reqs) {
            requireResource(REQ_PROTOCOL + req);
        }
        for (String fab : fabs) {
            requireResource(FAB_PROTOCOL + fab);
        }
        return resources;
    }

    public Collection<Resource> resolve(Resource systemBundle,
                                        boolean resolveOptionalImports) throws ResolutionException {
        // Resolve
        resources.put("system-bundle", systemBundle);

        List<org.osgi.service.repository.Repository> repos = new ArrayList<org.osgi.service.repository.Repository>();
        repos.add(new StaticRepository(resources.values()));
        repos.addAll(resourceRepos);

        ResolverImpl resolver = new ResolverImpl(new Slf4jResolverLog(LOGGER));
        ResolveContext context = new ResolveContextImpl(
                Collections.<Resource>singleton(requirements),
                Collections.<Resource>emptySet(),
                new AggregateRepository(repos),
                resolveOptionalImports);

        try {
            wiring = resolver.resolve(context);
        } catch (ResolutionException e) {
            // if its missing feature(s) then build a better error response the end user better understands
            // as today its a bit confusing with dummy/0.0.0 etc
            List<String> missing = new ArrayList<>();

            Collection<Requirement> reqs = e.getUnresolvedRequirements();
            Iterator<Requirement> it = reqs.iterator();
            while (it.hasNext()) {
                Requirement req = it.next();
                String type = (String) req.getAttributes().get(IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE);
                boolean isFeature = type != null && FeatureNamespace.TYPE_FEATURE.equals(type);
                String name = (String) req.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE);
                if (isFeature && name != null) {
                    missing.add(name);
                }
            }

            if (!missing.isEmpty()) {
                throw new ResolutionException("The following feature(s) may not exist or cannot be resolved: [" + Strings.join(missing, ", ") + "]", e, reqs);
            }
        }

        Map<String, Resource> deploy = new TreeMap<String, Resource>();
        for (Resource res : wiring.keySet()) {
            String uri = getUri(res);
            if (uri != null) {
                deploy.put(uri, res);
            }
        }
        return deploy.values();
    }

    public Map<Resource, List<Wire>> getWiring() {
        return wiring;
    }

    public void requireFeature(String feature, ResourceImpl resource) throws IOException {
        requireFeature(feature, resource, false);
    }

    public void requireFeature(String feature, ResourceImpl resource, boolean optional) throws IOException {
        // Find name and version range
        String[] split = feature.split("/");
        String name = split[0].trim();
        String version = (split.length > 1) ? split[1].trim() : "";
        VersionRange range = version.length() == 0
                        ? VersionRange.ANY_VERSION : new VersionRange(version);
        // Add requirement
        Map<String, String> dirs = new HashMap<>();
        dirs.put(REQUIREMENT_RESOLUTION_DIRECTIVE, optional ? RESOLUTION_OPTIONAL : RESOLUTION_MANDATORY);
        Map<String, Object> attrs = new HashMap<String, Object>();
        attrs.put(IdentityNamespace.IDENTITY_NAMESPACE, name);
        attrs.put(IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE, FeatureNamespace.TYPE_FEATURE);
        attrs.put(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE, range);
        resource.addRequirement(
                new RequirementImpl(resource, IdentityNamespace.IDENTITY_NAMESPACE,
                        dirs, attrs));
    }

    public void requireResource(String location) {
        Resource res = resources.get(location);
        if (res == null) {
            throw new IllegalStateException("Could not find resource for " + location);
        }
        List<Capability> caps = res.getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
        if (caps.size() != 1) {
            throw new IllegalStateException("Resource does not have a single " + IdentityNamespace.IDENTITY_NAMESPACE + " capability");
        }
        Capability cap = caps.get(0);
        // Add requirement
        Map<String, Object> attrs = new HashMap<String, Object>();
        attrs.put(IdentityNamespace.IDENTITY_NAMESPACE, cap.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE));
        attrs.put(IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE, cap.getAttributes().get(IdentityNamespace.CAPABILITY_TYPE_ATTRIBUTE));
        attrs.put(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE, new VersionRange((Version) cap.getAttributes().get(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE), true));
        requirements.addRequirement(
                new RequirementImpl(requirements, IdentityNamespace.IDENTITY_NAMESPACE,
                        Collections.<String, String>emptyMap(), attrs));

    }

    public void registerMatchingFeatures(String feature) throws IOException {
        // Find name and version range
        String[] split = feature.split("/");
        String name = split[0].trim();
        String version = (split.length > 1)
                ? split[1].trim() : Version.emptyVersion.toString();
        // Register matching features
        registerMatchingFeatures(name, new VersionRange(version));
    }

    public void registerMatchingFeatures(Feature feature) throws IOException {
        registerMatchingFeatures(feature.getName(), feature.getVersion());
    }

    public void registerMatchingFeatures(String name, String version) throws IOException {
        if (!version.startsWith("[") && !version.startsWith("(")) {
            Processor processor = new Processor();
            processor.setProperty("@", VersionTable.getVersion(version).toString());
            Macro macro = new Macro(processor);
            version = macro.process(featureRange);
        }
        registerMatchingFeatures(name, new VersionRange(version));
    }

    public void registerMatchingFeatures(String name, VersionRange range) throws IOException {
        for (Repository repo : repositories) {
            Feature[] features;
            try {
                features = repo.getFeatures();
            } catch (Exception e) {
                // This should not happen as the repository has been loaded already
                throw new IllegalStateException(e);
            }
            for (Feature f : features) {
                if (name.equals(f.getName())) {
                    Version v = VersionTable.getVersion(f.getVersion());
                    if (range.contains(v)) {
                        featuresToRegister.add(f);
                        for (Feature dep : f.getDependencies()) {
                            registerMatchingFeatures(dep);
                        }
                        for (BundleInfo bundle : f.getBundles()) {
                            downloadAndBuildResource(bundle.getLocation(), null);
                        }
                        for (Conditional cond : f.getConditional()) {
                            for (Feature dep : cond.getDependencies()) {
                                registerMatchingFeatures(dep);
                            }
                            for (BundleInfo bundle : cond.getBundles()) {
                                downloadAndBuildResource(bundle.getLocation(), null);
                            }
                        }
                    }
                }
            }
        }
    }

    public void downloadAndBuildResource(final String location, final DeploymentDownloadListener listener) throws IOException {
        if (location.startsWith(FAB_PROTOCOL)) {
            String url = location.substring(FAB_PROTOCOL.length());
            downloader.download(url, new AgentUtils.DownloadCallback() {
                @Override
                public void downloaded(File file, int pendings) throws Exception {
                    if (listener != null) {
                        listener.onDownload(file, pendings);
                    }
                    FabResolver resolver = fabResolverFactory.getResolver(file.toURI().toURL());
                    FabBundleInfo fabInfo = resolver.getInfo();

                    ResourceImpl resource = (ResourceImpl) manageResource(location, fabInfo.getManifest(), new StreamProvider.Fab(fabInfo));
                    for (String name : fabInfo.getFeatures()) {
                        registerMatchingFeatures(name);
                        requireFeature(name, resource);
                    }
                    for (DependencyTree dep : fabInfo.getBundles()) {
                        File depFile = dep.getJarFile();
                        Attributes attrs = getAttributes(dep.getJarURL().toString(), depFile);
                        if (attrs.getValue(Constants.BUNDLE_SYMBOLICNAME) != null) {
                            manageResource(getMvnUrl(dep), attrs, new StreamProvider.File(depFile));
                        }
                    }
                }
            });
        } else if (location.startsWith(REQ_PROTOCOL)) {
            try {
                ResourceImpl resource = new ResourceImpl(location, "dummy", Version.emptyVersion);
                for (Requirement req : ResourceBuilder.parseRequirement(resource, location.substring(REQ_PROTOCOL.length()))) {
                    resource.addRequirement(req);
                }
                resources.put(location, resource);
            } catch (BundleException e) {
                throw new IOException("Error parsing requirement", e);
            }
        } else {
            if (urlHandlersTimeout >= 0) {
                try {
                    // Find needed service ldap filters
                    List<String> filters = new ArrayList<String>();
                    int oldSize = -1;
                    String tmpUrl = location;
                    while (filters.size() > oldSize) {
                        oldSize = filters.size();
                        for (String protocol : PROTOCOLS) {
                            if (tmpUrl.startsWith(protocol + ":")) {
                                tmpUrl = tmpUrl.substring(protocol.length() + 1);
                                String filter = "(&(objectClass=org.osgi.service.url.URLStreamHandlerService)(url.handler.protocol=" + protocol + "))";
                                filters.add(filter);
                                break;
                            }
                        }
                    }
                    // Wait for services if needed
                    if (!filters.isEmpty()) {
                        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
                        List<ServiceTracker> trackers = new ArrayList<ServiceTracker>();
                        for (String filter : filters) {
                            Filter flt = FrameworkUtil.createFilter(filter);
                            ServiceTracker tracker = new ServiceTracker(context, flt, null);
                            tracker.open();
                            trackers.add(tracker);
                        }
                        long t0 = System.currentTimeMillis();
                        boolean hasAll = false;
                        while (!hasAll && (System.currentTimeMillis() - t0) < urlHandlersTimeout) {
                            hasAll = true;
                            for (ServiceTracker tracker : trackers) {
                                hasAll &= tracker.waitForService(100) != null;
                            }
                        }
                        for (ServiceTracker tracker : trackers) {
                            tracker.close();
                        }
                        if (!hasAll) {
                            throw new TimeoutException("Timed out waiting for URL handlers: ");
                        }
                    }
                } catch (Exception e) {
                    throw new IOException("Unable to download " + location, e);
                }
            }
            downloader.download(location, new AgentUtils.DownloadCallback() {
                @Override
                public void downloaded(File file, int pendings) throws Exception {
                    if (listener != null) {
                        listener.onDownload(file, pendings);
                    }
                    manageResource(location, file);
                }
            });
        }
    }

    private String getMvnUrl(DependencyTree dep) {
        String groupId = dep.getGroupId();
        String artifactId = dep.getArtifactId();
        String version = dep.getVersion();
        String classifier = dep.getClassifier();
        String extension = dep.getExtension();
        StringBuilder sb = new StringBuilder();
        sb.append("mvn:");
        sb.append(groupId);
        sb.append("/");
        sb.append(artifactId);
        sb.append("/");
        sb.append(version);
        if (!"".equals(classifier) || !"jar".equals(extension)) {
            sb.append("/");
            sb.append(extension);
        }
        if (!"".equals(classifier)) {
            sb.append("/");
            sb.append(classifier);
        }
        return sb.toString();
    }

    private Resource manageResource(String location, File file) throws Exception {
        Resource resource = resources.get(location);
        if (resource == null) {
            Attributes attributes = getAttributes(location, file);
            resource = manageResource(location, attributes, new StreamProvider.File(file));
        }
        return resource;
    }

    private Resource manageResource(String location, Attributes attributes, StreamProvider provider) throws Exception {
        Resource resource = resources.get(location);
        if (resource == null) {
            resource = createResource(location, attributes);
            resources.put(location, resource);
            providers.put(location, provider);
        }
        return resource;
    }

    private Resource createResource(String uri, Attributes attributes) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        for (Map.Entry attr : attributes.entrySet()) {
            headers.put(attr.getKey().toString(), attr.getValue().toString());
        }
        try {
            return ResourceBuilder.build(uri, headers);
        } catch (BundleException e) {
            throw new Exception("Unable to create resource for bundle " + uri, e);
        }
    }

    protected Attributes getAttributes(String uri, File file) throws Exception {
        Manifest man = null;
        try {
            man = Manifests.getManifest(file);
        } catch (Exception e) {
            if (file == null) {
                throw new IOException("Error - file must not be null. Source: \"" + uri + "\"", e);
            } else {
                throw new IOException("Error opening file \"" + file.getCanonicalPath() + "\". Source: \"" + uri + "\", size: " + file.length(), e);
            }
        }
        if (man == null) {
            throw new IllegalArgumentException("Resource " + uri + " does not contain a manifest");
        }
        Attributes attributes = man.getMainAttributes();
        return overrideAttributes(attributes, metadata);
    }

    public static Attributes overrideAttributes(Attributes attributes, Map<String, Map<VersionRange, Map<String, String>>> metadata) {
        String bsn = attributes.getValue(Constants.BUNDLE_SYMBOLICNAME);
        String vstr = attributes.getValue(Constants.BUNDLE_VERSION);
        if (bsn != null && vstr != null) {
            if (bsn.indexOf(';') > 0) {
                bsn = bsn.substring(0, bsn.indexOf(';'));
            }
            Version ver = VersionTable.getVersion(vstr);
            Map<VersionRange, Map<String, String>> ranges = metadata != null && bsn != null ? metadata.get(bsn) : null;
            if (ranges != null) {
                for (Map.Entry<VersionRange, Map<String, String>> entry2 : ranges.entrySet()) {
                    if (entry2.getKey().contains(ver)) {
                        for (Map.Entry<String, String> entry3 : entry2.getValue().entrySet()) {
                            String val;
                            if (entry3.getValue().startsWith("=")) {
                                val = entry3.getValue().substring(1);
                            } else {
                                val = attributes.getValue(entry3.getKey());
                                if (val != null) {
                                    val += "," + entry3.getValue();
                                } else {
                                    val = entry3.getValue();
                                }
                            }
                            attributes.putValue(entry3.getKey(), val);
                        }
                    }
                }
            }
        }
        return attributes;
    }

    public static void addIdentityRequirement(ResourceImpl resource, Resource required, boolean mandatory) {
        for (Capability cap : required.getCapabilities(null)) {
            if (cap.getNamespace().equals(IDENTITY_NAMESPACE)) {
                Map<String, Object> attributes = cap.getAttributes();
                Map<String, String> dirs = new HashMap<>();
                dirs.put(REQUIREMENT_RESOLUTION_DIRECTIVE, mandatory ? RESOLUTION_MANDATORY : RESOLUTION_OPTIONAL);
                Map<String, Object> attrs = new HashMap<>();
                attrs.put(IDENTITY_NAMESPACE, attributes.get(IDENTITY_NAMESPACE));
                attrs.put(CAPABILITY_TYPE_ATTRIBUTE, attributes.get(CAPABILITY_TYPE_ATTRIBUTE));
                Version version = (Version) attributes.get(CAPABILITY_VERSION_ATTRIBUTE);
                if (version != null) {
                    attrs.put(CAPABILITY_VERSION_ATTRIBUTE, new VersionRange(version, true));
                }
                resource.addRequirement(new RequirementImpl(resource, IDENTITY_NAMESPACE, dirs, attrs));
            }
        }
    }

}
