int main(int argc, char const *argv[])
{
    #ifdef __APPLE__
        #include <OpenCL/opencl.h>
    #endif
    print("Hello, World!\n");    
    return 0;
}
