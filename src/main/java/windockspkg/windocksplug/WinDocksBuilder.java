package windockspkg.windocksplug;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import javax.servlet.ServletException;
import java.io.IOException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.DataOutputStream;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;

/**
 * WinDocksBuilder {@link Builder}.
 *
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link WinDocksBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #image})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Robert Amenn
 */
public class WinDocksBuilder extends Builder implements SimpleBuildStep {

    private final String image;
    private final String ipaddress;

    // Fields in config.jelly must match the parameter names in the 
    // "DataBoundConstructor"
    @DataBoundConstructor
    public WinDocksBuilder(String ipaddress, String image) {
        this.ipaddress = ipaddress;
        this.image = image;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getIpaddress() {
        return ipaddress;
    }

    public String getImage() {
        return image;
    }

    @Override public void perform(Run<?,?> build, FilePath workspace, 
        Launcher launcher, TaskListener listener) throws IOException {

        Boolean result = CreateContainer(ipaddress, image);
        if(result == true)
        {
            listener.getLogger().println("Created container based on image " + 
                                         image  + 
                                         " on host with IP of " + ipaddress);
        }
        else
        {
            listener.getLogger().println("Failed to create container based on image " 
                                         + image + 
                                         " on host with IP of " + ipaddress);
        }   
    }

    private static Boolean CreateContainer(String ipAddress, String image) throws IOException
    {
        try
        {
            if(ipAddress == null)
            {
                throw new IllegalArgumentException("Invalid Docker server IP Address");
            }

            if(image == null)
            {
                throw new IllegalArgumentException("Invalid Docker Image");
            }   

            String payloadTemplate = "{\"Hostname\": \"\", \"Domainname\": \"\", "
            + "\"User\": \"\", \"AttachStdin\": false, \"User\": \"\","
            + "\"AttachStdin\": false, \"AttachStdout\": false, \"AttachStderr\": false, "
            + "\"PortSpecs\": null, \"ExposedPorts\": {},"
            + "\"Tty\": false, \"OpenStdin\": false, \"StdinOnce\": false, \"Env\": [], "
            + "\"Cmd\": null, \"Image\": \"%s\", \"Volumes\": {},"
            + "\"VolumeDriver\": \"\", \"WorkingDir\": \"\", \"Entrypoint\": null, "
            + "\"NetworkDisabled\": false, \"MacAddress\": \"\","
            + "\"OnBuild\": null, \"Labels\": {}, \"HostConfig\": {\"Binds\": null, "
            + "\"ContainerIDFile\": \"\", \"LxcConf\": [],"
            + "\"Memory\": 0, \"MemorySwap\": 0, \"CpuShares\": 0, \"CpuPeriod\": 0, "
            + "\"CpusetCpus\": \"\", \"CpusetMems\": \"\", \"CpuQuota\": 0,"
            + "\"BlkioWeight\": 0, \"OomKillDisable\": false, \"Privileged\": false, "
            + "\"PortBindings\": {}, \"Links\": null, \"PublishAllPorts\": false,"
            + "\"Dns\": null, \"DnsSearch\": null, \"ExtraHosts\": null, \"VolumesFrom\": null, "
            + "\"Devices\": [], \"NetworkMode\": \"bridge\","
            + "\"IpcMode\": \"\", \"PidMode\": \"\", \"UTSMode\": \"\", \"CapAdd\": null, "
            + "\"CapDrop\": null, \"RestartPolicy\": {\"Name\": \"no\","
            + "\"MaximumRetryCount\": 0}, \"SecurityOpt\": null, \"ReadonlyRootfs\": false, "
            + "\"Ulimits\": null, \"LogConfig\": {"
            + "\"Type\": \"\", \"Config\": {}}, \"CgroupParent\": \"\"}}";
        
            String payload = String.format(payloadTemplate, image);

            String urlCreateTemplate = "http://%s/containers/%s";
            URL urlCreate = new URL(String.format(urlCreateTemplate, ipAddress, "create"));

            HttpURLConnection connection = (HttpURLConnection)urlCreate.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.connect();

            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            wr.writeBytes(payload);
            wr.flush();
            wr.close();

            int responseCodeCreate = connection.getResponseCode();
            if(responseCodeCreate == 201)
            {
                String containerId = GetContainerId(connection.getInputStream());
                return StartContainer(ipAddress, containerId);
            }
            else
            {   
                return false;
            }
        }
        catch(Exception e)
        {
            return false;
        }
    }

    private static String GetContainerId(InputStream resultsStream) throws IOException, Exception
    {
        if(resultsStream == null)
        {   
            throw new IllegalArgumentException("Invalid HTTP Request's result stream");
        }
    
        StringBuilder sb = new StringBuilder();
        BufferedReader reader  = new BufferedReader(new InputStreamReader(resultsStream, "UTF-8"));

        String line = null;
        while ((line = reader.readLine()) != null)
        {
            sb.append(line);
        }
    
        reader.close();
    
        String[] parts = sb.toString().split("\\s+\\\\u0026\\s+");
        parts = parts[0].split("\\s+=\\s+");
    
        if(parts.length > 1)
        {
            return parts[1];
        }

        return null;
    }

    private static Boolean StartContainer(String ipAddress, String containerId) throws IOException, Exception
    {
        HttpURLConnection connectionStart = null;

        try {
            String urlStartTemplate = "http://%s/containers/%s/%s";
            URL urlStart = new URL(String.format(urlStartTemplate, ipAddress, containerId, "start"));
    
            connectionStart = (HttpURLConnection)urlStart.openConnection();
            connectionStart.setRequestMethod("POST");
            connectionStart.setRequestProperty("Content-Type", "application/json");
            connectionStart.setRequestProperty("Accept", "application/json");
            connectionStart.setDoOutput(true);
            connectionStart.connect();
    
            int responseCodeStart = connectionStart.getResponseCode();
            if(responseCodeStart == 204)
            {
                return true;
            }

            return false;

        } finally{
            if(connectionStart != null) 
            {
                connectionStart.disconnect();
            }
        }
    }
    
    /** Overridden for better type safety.
     *  If your plugin doesn't really define any property on Descriptor,
     *  you don't have to do this.
    */
    @Override public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link WinDocksBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/windockspkg/windocksplug/WinDocksBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */

    @Extension // Indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

       /** In order to load the persisted global configuration, you have to 
        * call load() in the constructor.
        */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'ipaddress'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckIpAddress(@QueryParameter String value) 
            throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.error("Please set a IP Address");

            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Setup Docker Container";
        }

        @Override public boolean configure(StaplerRequest req, JSONObject formData) 
            throws FormException {
            
            save();
            return super.configure(req,formData);
        }

        public ListBoxModel doFillImageItems(@QueryParameter String ipaddress) {

            ListBoxModel items = new ListBoxModel();

            try {
                if(ipaddress == null) 
                {
                    return items;
                }   

                String[] images = GetImages(ipaddress);
                for (String image : images) {
                    items.add(image);
                }

                return items;
            }
            catch(IOException ioe) {
                return items;
            }
            catch(IllegalArgumentException ae) { 
                return items;
            }
            catch(JsonIOException je) {
                return items;
            }
            catch(JsonSyntaxException jse) {
                return items;
            }
            catch(Exception e) { 
                return items;
            }
        }

        /**  Inner class used to read from JSON */
        private static class Image { 
            // Need to initalize here to avoid findbugs-maven-plugin report of referencing of uninstallized RepoTags
            // Correct value will be set by JSON parser...
            String[] RepoTags = new String[1];  
        }

        private String[] GetImages(String ipAddress) 
            throws IOException, IllegalArgumentException, JsonIOException, JsonSyntaxException {    

            HttpURLConnection connection = null;

            try {       
                if(ipAddress == null) 
                { 
                    throw new IllegalArgumentException("Invalid Docker server IP Address");
                }

                String urlString = String.format("http://%s/images/json", ipAddress);
                URL url = new URL(urlString);
    
                connection = (HttpURLConnection)url.openConnection();
                connection.setRequestMethod("GET");
                connection.connect();

                InputStream stream = connection.getInputStream();
                InputStreamReader streamReader = new InputStreamReader(stream, "UTF-8");

                Gson gson = new GsonBuilder().create();
                Image[] Images = gson.fromJson(streamReader, Image[].class);
                ArrayList<String> imageNames = new ArrayList<String>();

                for(Image image : Images)
                {
                    if(image.RepoTags != null)
                    {
                        for( String repoTag : image.RepoTags)
                        {   
                            String[] tagParts = repoTag.split(":");
                            if(tagParts.length > 0)
                            {
                                /** Image name is the first part of a tag... */
                                imageNames.add(tagParts[0]);
                            }
                        }
                    }
                }

                return imageNames.toArray(new String[1]);

            } finally {
                if(connection != null)
                {
                    connection.disconnect();
                }    
            }
        }   
    }
}
